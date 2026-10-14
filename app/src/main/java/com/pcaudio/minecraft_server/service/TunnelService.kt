package com.pcaudio.minecraft_server.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pcaudio.minecraft_server.R
import com.pcaudio.minecraft_server.core.ApiKeyStore
import com.pcaudio.minecraft_server.core.PlayitAgentManager
import com.pcaudio.minecraft_server.ui.NetworkActivity
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TunnelService : Service() {

    companion object {
        const val CHANNEL_ID = "tunnel_channel"
        const val NOTIF_ID = 3
        const val ACTION_STOP = "com.pcaudio.minecraft_server.action.TUNNEL_STOP"
        const val AGENT_STABLE_MS = 60000L
        val RESTART_DELAYS_MS = longArrayOf(5000L, 15000L, 30000L, 60000L, 120000L)

        @Volatile
        var isAgentRunning: Boolean = false

        private val logListeners = mutableListOf<(String) -> Unit>()
        private val stateListeners = mutableListOf<(Boolean) -> Unit>()
        private val listenersLock = Any()
        private val fileLock = Any()

        fun addLogListener(listener: (String) -> Unit) {
            synchronized(listenersLock) {
                if (!logListeners.contains(listener)) {
                    logListeners.add(listener)
                }
            }
        }

        fun removeLogListener(listener: (String) -> Unit) {
            synchronized(listenersLock) {
                logListeners.remove(listener)
            }
        }

        fun addStateListener(listener: (Boolean) -> Unit) {
            synchronized(listenersLock) {
                if (!stateListeners.contains(listener)) {
                    stateListeners.add(listener)
                }
            }
        }

        fun removeStateListener(listener: (Boolean) -> Unit) {
            synchronized(listenersLock) {
                stateListeners.remove(listener)
            }
        }

        private fun dispatchLog(line: String) {
            val snapshot = synchronized(listenersLock) { logListeners.toList() }
            for (listener in snapshot) {
                try {
                    listener(line)
                } catch (e: Exception) {
                    Log.w("TunnelService", "Listener di log: " + e.message)
                }
            }
        }

        private fun pushState() {
            val running = isAgentRunning
            val snapshot = synchronized(listenersLock) { stateListeners.toList() }
            for (listener in snapshot) {
                try {
                    listener(running)
                } catch (e: Exception) {
                    Log.w("TunnelService", "Listener di stato: " + e.message)
                }
            }
        }
    }

    private lateinit var agentManager: PlayitAgentManager
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var consoleFile: File? = null

    @Volatile
    private var restartAttempts = 0

    @Volatile
    private var agentStartedAt = 0L

    @Volatile
    private var stopping = false

    @Volatile
    private var batteryDone = false

    override fun onCreate() {
        super.onCreate()
        val playitDir = File(filesDir, "playit")
        playitDir.mkdirs()
        consoleFile = File(playitDir, "agent_console.log")
        agentManager = PlayitAgentManager(this) { line -> appendLog(line) }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.tech_channel_tunnel_name), NotificationManager.IMPORTANCE_LOW)
            channel.description = getString(R.string.tech_channel_tunnel_desc)
            notificationManager.createNotificationChannel(channel)
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mcserverhost:tunnel").apply { setReferenceCounted(false) }
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mcserverhost:tunnel_wifi").apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        stopping = false
        restartAttempts = 0
        startForeground(NOTIF_ID, buildNotification(getString(R.string.tech_notif_tunnel_starting)))
        wakeLock?.acquire()
        wifiLock?.acquire()
        val secretExtra = intent?.getStringExtra("secret")
        executor.execute {
            if (!batteryDone) {
                batteryDone = true
                applyBatteryExemption()
            }
            doStartAgent(secretExtra)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        executor.execute {
            agentManager.stop()
            isAgentRunning = false
            releaseLocks()
            pushState()
        }
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun doStartAgent(secretExtra: String?) {
        if (stopping || agentManager.isRunning()) {
            pushState()
            return
        }
        agentStartedAt = 0L
        val secret = if (!secretExtra.isNullOrBlank()) {
            secretExtra
        } else {
            try {
                ApiKeyStore(this).getPlayitSecret()
            } catch (e: Exception) {
                appendLog(getString(R.string.tech_log_secret_read_failed, e.message))
                null
            }
        }
        if (secret.isNullOrBlank()) {
            appendLog(getString(R.string.tech_log_secret_missing))
            updateNotification(getString(R.string.tech_log_secret_missing))
            releaseLocks()
            pushState()
            return
        }
        val started = agentManager.start(secret) {
            if (!stopping) {
                appendLog(getString(R.string.tech_log_agent_unexpected))
                scheduleRestart()
            }
        }
        if (started) {
            agentStartedAt = System.currentTimeMillis()
            isAgentRunning = true
            appendLog(getString(R.string.tech_log_agent_started))
            updateNotification(getString(R.string.tech_notif_tunnel_active))
            pushState()
        } else {
            appendLog(getString(R.string.tech_log_agent_start_failed))
            scheduleRestart()
        }
    }

    private fun scheduleRestart() {
        isAgentRunning = false
        if (stopping) {
            return
        }
        if (agentStartedAt > 0L && System.currentTimeMillis() - agentStartedAt > AGENT_STABLE_MS) {
            restartAttempts = 0
        }
        if (restartAttempts >= RESTART_DELAYS_MS.size) {
            appendLog(getString(R.string.tech_log_restarts_failed))
            updateNotification(getString(R.string.tech_notif_tunnel_inactive))
            releaseLocks()
            pushState()
            return
        }
        val delay = RESTART_DELAYS_MS[restartAttempts]
        restartAttempts++
        appendLog(getString(R.string.tech_log_restart_in, delay / 1000))
        updateNotification(getString(R.string.tech_notif_restart_in, delay / 1000))
        pushState()
        handler.postDelayed({ executor.execute { doStartAgent(null) } }, delay)
    }

    private fun stopEverything() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        startForeground(NOTIF_ID, buildNotification(getString(R.string.tech_notif_tunnel_stopping)))
        executor.execute {
            agentManager.stop()
            isAgentRunning = false
            appendLog(getString(R.string.tech_log_agent_stopped))
            releaseLocks()
            pushState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun applyBatteryExemption() {
        try {
            val rootProcess = ProcessBuilder("su", "-c", "/system/bin/cmd deviceidle whitelist +" + packageName).redirectErrorStream(true).start()
            val finished = rootProcess.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                rootProcess.destroyForcibly()
                appendLog(getString(R.string.tech_log_battery_timeout))
            } else {
                val output = rootProcess.inputStream.bufferedReader().readText().trim()
                if (rootProcess.exitValue() == 0) {
                    appendLog(getString(R.string.tech_log_battery_applied))
                } else {
                    appendLog(getString(R.string.tech_log_battery_failed, output))
                }
            }
        } catch (e: Exception) {
            appendLog(getString(R.string.tech_log_battery_failed, e.message))
        }
        try {
            val batteryIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + packageName))
            batteryIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(batteryIntent)
        } catch (e: Exception) {
            appendLog(getString(R.string.tech_log_battery_open_failed, e.message))
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, TunnelService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val openIntent = Intent(this, NetworkActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(getString(R.string.tech_notif_tunnel_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.tech_notif_stop), stopPendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIF_ID, buildNotification(text))
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w("TunnelService", "Rilascio wake lock: " + e.message)
        }
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {
            Log.w("TunnelService", "Rilascio wifi lock: " + e.message)
        }
    }

    private fun appendLog(line: String) {
        val file = consoleFile ?: return
        try {
            file.parentFile?.mkdirs()
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(Date())
            synchronized(fileLock) {
                FileWriter(file, true).use { writer -> writer.write(stamp + " " + line + "\n") }
            }
        } catch (e: Exception) {
            Log.w("TunnelService", "Scrittura log: " + e.message)
        }
        dispatchLog(line)
    }
}