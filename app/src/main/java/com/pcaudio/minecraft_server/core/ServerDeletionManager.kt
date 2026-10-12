package com.pcaudio.minecraft_server.core

import android.content.Context
import android.content.Intent
import com.pcaudio.minecraft_server.service.MinecraftServerService

class ServerDeletionManager(private val context: Context) {

    private val repository = ServerRepository(context)

    fun deleteServer(id: String, onResult: (Boolean, String?) -> Unit) {
        stopServerIfRunning()

        Thread {
            Thread.sleep(500)
            killAnyOrphanProcess()
            Thread.sleep(500)

            val config = repository.listServers().firstOrNull { it.id == id }
            if (config == null) {
                onResult(false, "Server non trovato")
                return@Thread
            }

            val serverDir = java.io.File(config.directoryPath)
            var deleted = true

            if (serverDir.exists()) {
                deleted = try {
                    serverDir.deleteRecursively()
                } catch (e: Exception) {
                    false
                }

                if (!deleted || serverDir.exists()) {
                    try {
                        val process = ProcessBuilder("su", "-c", "rm -rf '${serverDir.absolutePath}'").start()
                        val exitCode = process.waitFor()
                        deleted = exitCode == 0 && !serverDir.exists()
                    } catch (e: Exception) {
                        deleted = false
                    }
                }
            }

            if (deleted) {
                repository.deleteServer(id)
                onResult(true, null)
            } else {
                onResult(false, "Impossibile eliminare i file del server, potrebbero essere in uso")
            }
        }.start()
    }

    private fun killAnyOrphanProcess() {
        try {
            val process = ProcessBuilder("su", "-c", "pkill -9 -f '/files/servers/'").start()
            process.waitFor()
        } catch (e: Exception) {
        }
    }

    private fun stopServerIfRunning() {
        val intent = Intent(context, MinecraftServerService::class.java)
        intent.action = MinecraftServerService.ACTION_STOP
        context.startService(intent)
    }
}