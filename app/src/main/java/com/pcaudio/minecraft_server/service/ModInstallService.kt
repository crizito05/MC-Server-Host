package com.pcaudio.minecraft_server.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pcaudio.minecraft_server.core.CurseForgeApi
import com.pcaudio.minecraft_server.core.ModCatalog
import com.pcaudio.minecraft_server.core.ModLoader
import com.pcaudio.minecraft_server.core.ModVersionFile
import com.pcaudio.minecraft_server.core.ModrinthApi
import java.io.File

class ModInstallService : Service() {

    companion object {
        const val CHANNEL_ID = "mod_install_channel"
        const val NOTIF_ID = 2
        const val PART_SUFFIX = ".part"
        const val MIN_VALID_BYTES = 1024L
        const val EXTRA_IS_UPDATE = "is_update"

        private val resultListeners = mutableSetOf<(Boolean, String, String?, Boolean) -> Unit>()

        fun addResultListener(listener: (Boolean, String, String?, Boolean) -> Unit) { resultListeners.add(listener) }
        fun removeResultListener(listener: (Boolean, String, String?, Boolean) -> Unit) { resultListeners.remove(listener) }

        private fun notifyResult(success: Boolean, modTitle: String, error: String?, isUpdate: Boolean) {
            resultListeners.forEach { it(success, modTitle, error, isUpdate) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modTitle = intent?.getStringExtra("mod_title") ?: return START_NOT_STICKY
        val projectId = intent.getStringExtra("project_id") ?: return START_NOT_STICKY
        val sourceProvider = intent.getStringExtra("source_provider") ?: return START_NOT_STICKY
        val modsDirPath = intent.getStringExtra("mods_dir") ?: return START_NOT_STICKY
        val serverDirPath = intent.getStringExtra("server_dir") ?: return START_NOT_STICKY
        val chosenFilename = intent.getStringExtra("chosen_filename") ?: return START_NOT_STICKY
        val chosenDownloadUrl = intent.getStringExtra("chosen_download_url") ?: return START_NOT_STICKY
        val loaderName = intent.getStringExtra("loader")
        val minecraftVersion = intent.getStringExtra("minecraft_version")
        val curseForgeKey = intent.getStringExtra("curseforge_key")
        val oldFilename = intent.getStringExtra("old_filename")
        val isUpdate = intent.getBooleanExtra(EXTRA_IS_UPDATE, false)
        val requiredDeps = intent.getStringArrayListExtra("required_deps") ?: arrayListOf()

        val notificationTitle = if (isUpdate) "Aggiornamento: $modTitle" else "Installazione: $modTitle"
        startForeground(NOTIF_ID, buildNotification(notificationTitle, "Download in corso...", 0, true))

        Thread {
            try {
                val tracker = com.pcaudio.minecraft_server.core.InstalledModsTracker(File(serverDirPath))

                if (requiredDeps.isNotEmpty() && loaderName != null && minecraftVersion != null) {
                    val loader = com.pcaudio.minecraft_server.core.ModLoader.valueOf(loaderName)
                    val modrinth = com.pcaudio.minecraft_server.core.ModrinthApi()

                    for (depProjectId in requiredDeps) {
                        if (tracker.isInstalled(depProjectId, "Modrinth")) continue

                        updateNotification(notificationTitle, "Installazione dipendenza richiesta...", 10)
                        try {
                            val depVersions = modrinth.getVersions(depProjectId, loader, minecraftVersion)
                            val depBest = depVersions.firstOrNull() ?: continue

                            val modsDir = File(modsDirPath)
                            modsDir.mkdirs()
                            downloadFile(modsDir, depBest.filename, depBest.downloadUrl, "Modrinth", null) { }
                            tracker.recordInstall(depProjectId, "Modrinth", depBest.filename)
                        } catch (e: Exception) {
                        }
                    }
                }

                updateNotification(notificationTitle, "Download ${chosenFilename}...", 30)
                val modsDir = File(modsDirPath)
                modsDir.mkdirs()
                downloadFile(modsDir, chosenFilename, chosenDownloadUrl, sourceProvider, curseForgeKey) { percent ->
                    updateNotification(notificationTitle, "Download $percent%", 30 + (percent * 0.7).toInt())
                }

                tracker.recordInstall(projectId, sourceProvider, chosenFilename)

                if (!oldFilename.isNullOrBlank() && oldFilename != chosenFilename) {
                    val oldFile = File(modsDir, oldFilename)
                    if (oldFile.exists()) {
                        val removed = try {
                            oldFile.delete()
                        } catch (e: Exception) {
                            false
                        }
                        if (!removed || oldFile.exists()) {
                            try {
                                val exitCode = ProcessBuilder("su", "-c", "rm -f '${oldFile.absolutePath}'").start().waitFor()
                                if (exitCode != 0 || oldFile.exists()) {
                                    notifyResult(false, modTitle, "Nuova versione installata, ma il file precedente $oldFilename non è stato eliminato. Eliminalo a mano dalla cartella mods, altrimenti il server caricherà la mod due volte.", isUpdate)
                                    return@Thread
                                }
                            } catch (e: Exception) {
                                notifyResult(false, modTitle, "Nuova versione installata, ma il file precedente $oldFilename non è stato eliminato: ${e.message}", isUpdate)
                                return@Thread
                            }
                        }
                    }
                }

                updateNotification(notificationTitle, "Completato", 100)
                notifyResult(true, modTitle, null, isUpdate)
            } catch (e: Exception) {
                notifyResult(false, modTitle, e.message, isUpdate)
            } finally {
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun downloadFile(
        modsDir: File,
        filename: String,
        url: String,
        sourceProvider: String,
        curseForgeKey: String?,
        onProgress: (Int) -> Unit
    ) {
        val target = File(modsDir, filename)
        val temp = File(modsDir, filename + PART_SUFFIX)
        if (temp.exists()) temp.delete()

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val requestBuilder = okhttp3.Request.Builder().url(url)
        if (sourceProvider == "CurseForge" && curseForgeKey != null) {
            requestBuilder.header("x-api-key", curseForgeKey)
        }

        val response = client.newCall(requestBuilder.build()).execute()
        val body = response.body ?: throw IllegalStateException("Download fallito: nessuna risposta per $filename")

        val totalBytes = body.contentLength()
        var downloaded = 0L

        body.byteStream().use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0) {
                        onProgress(((downloaded * 100) / totalBytes).toInt())
                    }
                }
            }
        }

        if (totalBytes > 0 && downloaded != totalBytes) {
            temp.delete()
            throw IllegalStateException("Download incompleto di $filename: ricevuti $downloaded byte su $totalBytes")
        }
        if (downloaded < MIN_VALID_BYTES) {
            temp.delete()
            throw IllegalStateException("Download sospetto di $filename: solo $downloaded byte ricevuti")
        }
        if (target.exists()) target.delete()

        val renamed = temp.renameTo(target)
        if (!renamed) {
            try {
                temp.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                temp.delete()
            } catch (e: Exception) {
                temp.delete()
                throw IllegalStateException("Impossibile rinominare $filename.part in $filename: ${e.message}")
            }
        }
        if (!target.exists() || target.length() < MIN_VALID_BYTES) {
            throw IllegalStateException("File $filename non valido al termine del download")
        }
    }

    private fun buildNotification(title: String, status: String, progress: Int, indeterminate: Boolean): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, status: String, progress: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(title, status, progress, false))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Installazione mod", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIF_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}