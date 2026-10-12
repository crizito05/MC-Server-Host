package com.pcaudio.minecraft_server.core

import android.content.Context
import java.io.File

sealed class CreationStep {
    data class Progress(val message: String) : CreationStep()
    data class Completed(val config: ServerConfig) : CreationStep()
    data class Failed(val error: String) : CreationStep()
}

class ServerCreationManager(private val context: Context) {

    private val repository = ServerRepository(context)

    fun createServer(
        name: String,
        loader: ModLoader,
        minecraftVersion: String,
        loaderVersion: String?,
        ramMb: Int,
        useRoot: Boolean,
        onStep: (CreationStep) -> Unit
    ) {
        Thread {
            var serverDir: File? = null
            val progressLines = mutableListOf<String>()

            fun progress(message: String) {
                progressLines.add(message)
                onStep(CreationStep.Progress(message))
            }

            try {
                progress("Rilevamento versione Java richiesta")
                val jdkMajor = JdkVersionResolver().requiredJdkMajorVersion(minecraftVersion)
                progress("Java $jdkMajor richiesto per Minecraft $minecraftVersion")

                val bootstrap: JavaRuntimeProvider =
                    if (useRoot) RootTermuxBootstrap(context, jdkMajor) else TermuxBootstrap(context)

                bootstrap.ensureRuntimeData { line -> progress(line) }

                if (!bootstrap.isInstalled()) {
                    onStep(CreationStep.Failed("Runtime Java non disponibile dopo la preparazione"))
                    return@Thread
                }

                val dir = repository.newServerDirectory(name)
                serverDir = dir
                val provisioner = ServerProvisioner(context, bootstrap)

                progress("Provisioning server $name")
                provisioner.provision(
                    serverDir = dir,
                    loader = loader,
                    minecraftVersion = minecraftVersion,
                    loaderVersion = loaderVersion,
                    onProgress = { line -> progress(line) }
                )

                val config = ServerConfig(
                    id = repository.generateId(),
                    name = name,
                    loader = loader,
                    minecraftVersion = minecraftVersion,
                    loaderVersion = loaderVersion,
                    ramMb = ramMb,
                    directoryPath = dir.absolutePath,
                    usesRoot = useRoot,
                    jdkMajorVersion = jdkMajor
                )
                repository.saveServer(config)

                onStep(CreationStep.Completed(config))
            } catch (e: Exception) {
                val error = e.message ?: "Errore sconosciuto"
                saveFailureLog(serverDir, progressLines, error)
                serverDir?.let { repository.deleteDirectoryRobust(it) }
                onStep(CreationStep.Failed(error))
            }
        }.start()
    }

    private fun readLogFile(file: File): String {
        return try {
            file.readText()
        } catch (e: Exception) {
            try {
                val process = ProcessBuilder("su", "-c", "cat '${file.absolutePath}'").start()
                val text = process.inputStream.bufferedReader().readText()
                process.waitFor()
                text
            } catch (e2: Exception) {
                "(file non leggibile)"
            }
        }
    }

    private fun saveFailureLog(serverDir: File?, progressLines: List<String>, error: String) {
        try {
            val logsDir = File(context.filesDir, "creation_logs")
            logsDir.mkdirs()
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val target = File(logsDir, "creation_$stamp.log")

            val sb = StringBuilder()
            sb.appendLine("ERRORE: $error")
            sb.appendLine()
            sb.appendLine("--- Output creazione ---")
            progressLines.forEach { sb.appendLine(it) }

            serverDir?.listFiles { f -> f.isFile && f.name.endsWith(".log") }?.forEach { logFile ->
                sb.appendLine()
                sb.appendLine("--- ${logFile.name} ---")
                sb.appendLine(readLogFile(logFile))
            }

            target.writeText(sb.toString())
        } catch (e: Exception) {
        }
    }
}