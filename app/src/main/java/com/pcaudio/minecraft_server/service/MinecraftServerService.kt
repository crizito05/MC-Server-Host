package com.pcaudio.minecraft_server.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.pcaudio.minecraft_server.core.LaunchCommandResolver
import com.pcaudio.minecraft_server.core.TermuxBootstrap
import java.io.File

class MinecraftServerService : Service() {

    private lateinit var wakeLock: PowerManager.WakeLock
    private var serverProcess: Process? = null
    private lateinit var bootstrap: TermuxBootstrap
    private val mainHandler = Handler(Looper.getMainLooper())
    private var logFileWriter: java.io.FileWriter? = null

    companion object {
        const val CHANNEL_ID = "mc_server_channel"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.pcaudio.minecraft_server.STOP"
        const val ACTION_COMMAND = "com.pcaudio.minecraft_server.COMMAND"
        const val EXTRA_COMMAND = "command"

        @Volatile
        var runningServerId: String? = null
            private set

        private var instance: MinecraftServerService? = null
        private val logListeners = mutableSetOf<(String) -> Unit>()
        private val stateListeners = mutableSetOf<(Boolean) -> Unit>()
        private val logBuffer = mutableListOf<String>()
        private const val MAX_LOG_LINES = 500

        fun addLogListener(listener: (String) -> Unit) { logListeners.add(listener) }
        fun removeLogListener(listener: (String) -> Unit) { logListeners.remove(listener) }
        fun addStateListener(listener: (Boolean) -> Unit) { stateListeners.add(listener) }
        fun removeStateListener(listener: (Boolean) -> Unit) { stateListeners.remove(listener) }
        fun getLogBuffer(): List<String> = synchronized(logBuffer) { logBuffer.toList() }
        fun isRunning(serverId: String): Boolean = runningServerId == serverId

        fun sendCommand(command: String) {
            instance?.writeCommand(command)
        }

        private fun pushLog(line: String) {
            synchronized(logBuffer) {
                logBuffer.add(line)
                if (logBuffer.size > MAX_LOG_LINES) logBuffer.removeAt(0)
            }
            logListeners.forEach { it(line) }
        }

        private fun pushState(running: Boolean) {
            stateListeners.forEach { it(running) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        bootstrap = TermuxBootstrap(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mcserverhost:server")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_COMMAND -> {
                val command = intent.getStringExtra(EXTRA_COMMAND)
                if (command != null) writeCommand(command)
                return START_NOT_STICKY
            }
        }

        val serverDir = intent?.getStringExtra("server_dir") ?: return START_NOT_STICKY
        val serverId = intent.getStringExtra("server_id")
        val ramMb = intent.getIntExtra("ram_mb", 2048)

        synchronized(logBuffer) { logBuffer.clear() }
        startForeground(NOTIF_ID, buildNotification("Avvio in corso"))
        wakeLock.acquire()
        runningServerId = serverId
        pushState(true)
        startServerProcess(serverDir, ramMb, serverId)

        return START_STICKY
    }

    private fun startServerProcess(serverDir: String, ramMb: Int, serverId: String?) {
        Thread {
            val repository = com.pcaudio.minecraft_server.core.ServerRepository(this)
            val config = repository.listServers().firstOrNull { it.id == serverId }
            val useRoot = config?.usesRoot ?: false
            val jdkMajor = config?.jdkMajorVersion ?: 21

            val logsDir = File(serverDir, "app_logs")
            logsDir.mkdirs()
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault()).format(java.util.Date())
            val logFile = File(logsDir, "session_$timestamp.log")
            logFileWriter = try {
                java.io.FileWriter(logFile, true)
            } catch (e: Exception) {
                null
            }

            fun log(line: String) {
                pushLog(line)
                try {
                    logFileWriter?.appendLine(line)
                    logFileWriter?.flush()
                } catch (e: Exception) {
                }
            }

            if (useRoot) {
                try {
                    val checkCmd = ProcessBuilder("su", "-c", "pgrep -f 'java.*files/servers'").start()
                    val existingPids = checkCmd.inputStream.bufferedReader().readText().trim()
                    checkCmd.waitFor()

                    if (existingPids.isNotBlank()) {
                        log("Trovati processi server residui, terminazione in corso")
                        val killCmd = ProcessBuilder("su", "-c", "pkill -9 -f 'java.*files/servers'").start()
                        killCmd.waitFor()
                        Thread.sleep(1500)
                    }
                } catch (e: Exception) {
                }
            }

            val bootstrap: com.pcaudio.minecraft_server.core.JavaRuntimeProvider =
                if (useRoot) com.pcaudio.minecraft_server.core.RootTermuxBootstrap(this, jdkMajor)
                else com.pcaudio.minecraft_server.core.TermuxBootstrap(this)

            if (!bootstrap.isInstalled()) {
                log("Runtime Java non trovato, preparazione in corso...")
                bootstrap.ensureRuntimeData { line -> log(line) }
            }

            if (!bootstrap.isInstalled()) {
                log("Errore: runtime Java non disponibile dopo la preparazione")
                runningServerId = null
                pushState(false)
                if (wakeLock.isHeld) wakeLock.release()
                logFileWriter?.close()
                mainHandler.post { stopSelf() }
                return@Thread
            }

            if (useRoot && bootstrap is com.pcaudio.minecraft_server.core.RootTermuxBootstrap) {
                bootstrap.remountExec { line -> log(line) }
            }

            if (useRoot) {
                try {
                    val chmodCmd = ProcessBuilder("su", "-c", "chmod -R 755 '${serverDir}' && chmod -R 755 '${bootstrap.javaBinary().parentFile?.absolutePath}'").start()
                    chmodCmd.waitFor()
                } catch (e: Exception) {
                }
            }

            if (useRoot) {
                try {
                    val lockFile = File(serverDir, "world/session.lock")
                    if (lockFile.exists()) {
                        lockFile.delete()
                    }
                } catch (e: Exception) {
                }
            }

            val javaBin = bootstrap.javaBinary()
            val resolver = LaunchCommandResolver()
            val command = resolver.resolve(File(serverDir), javaBin, ramMb)

            val pb: ProcessBuilder
            if (useRoot) {
                val innerCommand = command.joinToString(" ") { "\"$it\"" }
                val fullShellCommand = "cd \"$serverDir\" && export LD_LIBRARY_PATH=\"${bootstrap.libraryDir().absolutePath}\" && export HOME=\"${bootstrap.homeDir().absolutePath}\" && $innerCommand"
                pb = ProcessBuilder("su", "-c", fullShellCommand)
                log("Avvio processo (root): $fullShellCommand")
            } else {
                pb = ProcessBuilder(command)
                pb.directory(File(serverDir))
                pb.environment()["LD_LIBRARY_PATH"] = bootstrap.libraryDir().absolutePath
                pb.environment()["HOME"] = bootstrap.homeDir().absolutePath
                log("Avvio processo: ${command.joinToString(" ")}")
            }
            pb.redirectErrorStream(true)

            serverProcess = try {
                pb.start()
            } catch (e: Exception) {
                log("Errore avvio processo: ${e.message}")
                runningServerId = null
                pushState(false)
                if (wakeLock.isHeld) wakeLock.release()
                logFileWriter?.close()
                mainHandler.post { stopSelf() }
                return@Thread
            }

            serverProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                log(line)
                if (line.contains("Done") && line.contains("For help")) {
                    mainHandler.post { updateNotification("Server online") }
                }
            }
            val exitCode = serverProcess?.waitFor() ?: -1
            log("Processo terminato (codice $exitCode)")
            runningServerId = null
            pushState(false)
            if (wakeLock.isHeld) wakeLock.release()
            logFileWriter?.close()
            logFileWriter = null
        }.start()
    }

    private fun writeCommand(command: String) {
        try {
            serverProcess?.outputStream?.let {
                it.write("$command\n".toByteArray())
                it.flush()
                pushLog("> $command")
            }
        } catch (e: Exception) {
            pushLog("Errore invio comando: ${e.message}")
        }
    }

    private fun stopServer() {
        writeCommand("stop")
        Thread {
            val exited = serverProcess?.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) ?: true
            if (exited != true) {
                pushLog("Il server non ha risposto allo stop, terminazione forzata")
                try {
                    ProcessBuilder("su", "-c", "pkill -f server.jar").start().waitFor()
                } catch (e: Exception) {
                }
            }
            runningServerId = null
            pushState(false)
            if (wakeLock.isHeld) wakeLock.release()
        }.start()
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Minecraft Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Server Minecraft", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}