package com.pcaudio.minecraft_server.core

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class ServerProvisioner(
    private val context: Context,
    private val bootstrap: JavaRuntimeProvider
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun provision(
        serverDir: File,
        loader: ModLoader,
        minecraftVersion: String,
        loaderVersion: String? = null,
        onProgress: (String) -> Unit
    ) {
        serverDir.mkdirs()
        writeEula(serverDir)
        File(serverDir, "mods").mkdirs()

        when (loader) {
            ModLoader.VANILLA -> installVanilla(serverDir, minecraftVersion, onProgress)
            ModLoader.FORGE -> installForge(serverDir, minecraftVersion, requireNotNull(loaderVersion), onProgress)
            ModLoader.NEOFORGE -> installNeoForge(serverDir, minecraftVersion, requireNotNull(loaderVersion), onProgress)
            ModLoader.FABRIC -> installFabric(serverDir, minecraftVersion, loaderVersion, onProgress)
            //ModLoader.QUILT -> installQuilt(serverDir, minecraftVersion, loaderVersion, onProgress)
        }
    }

    private fun writeEula(serverDir: File) {
        File(serverDir, "eula.txt").writeText("eula=true\n")
    }

    private fun downloadFile(url: String, target: File, onProgress: (String) -> Unit) {
        onProgress("Download ${target.name}")
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.body ?: throw IllegalStateException("Download fallito: $url")

        val totalBytes = body.contentLength()
        var downloadedBytes = 0L
        var lastReportedPercent = -1

        body.byteStream().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    if (totalBytes > 0) {
                        val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                        if (percent != lastReportedPercent && percent % 10 == 0) {
                            lastReportedPercent = percent
                            onProgress("${target.name}: $percent%")
                        }
                    }
                }
            }
        }
    }

    private fun runJavaJar(serverDir: File, jarName: String, args: List<String>, onProgress: (String) -> Unit) {
        val javaBin = bootstrap.javaBinary()
        val isRoot = bootstrap is RootTermuxBootstrap

        val tmpDir = File(context.cacheDir, "javatmp")
        tmpDir.mkdirs()

        val process: Process
        if (isRoot) {
            val cmd = mutableListOf("\"${javaBin.absolutePath}\"", "-Djava.io.tmpdir=\"${tmpDir.absolutePath}\"", "-jar", "\"$jarName\"")
            cmd.addAll(args.map { "\"$it\"" })
            val innerCommand = cmd.joinToString(" ")
            val fullShellCommand = "cd \"${serverDir.absolutePath}\" && export LD_LIBRARY_PATH=\"${bootstrap.libraryDir().absolutePath}\" && export HOME=\"${bootstrap.homeDir().absolutePath}\" && export TMPDIR=\"${tmpDir.absolutePath}\" && $innerCommand"
            val pb = ProcessBuilder("su", "-c", fullShellCommand)
            pb.redirectErrorStream(true)
            process = pb.start()
        } else {
            val cmd = mutableListOf(javaBin.absolutePath, "-Djava.io.tmpdir=${tmpDir.absolutePath}", "-jar", jarName)
            cmd.addAll(args)
            val pb = ProcessBuilder(cmd)
            pb.directory(serverDir)
            pb.environment()["LD_LIBRARY_PATH"] = bootstrap.libraryDir().absolutePath
            pb.environment()["HOME"] = bootstrap.homeDir().absolutePath
            pb.environment()["TMPDIR"] = tmpDir.absolutePath
            pb.redirectErrorStream(true)
            process = pb.start()
        }

        val lastLines = ArrayDeque<String>()
        process.inputStream.bufferedReader().forEachLine { line ->
            onProgress(line)
            lastLines.addLast(line)
            if (lastLines.size > 15) lastLines.removeFirst()
        }
        val exit = process.waitFor()
        if (exit != 0) {
            val details = if (lastLines.isEmpty()) "nessun output dall'installer" else lastLines.joinToString("\n")
            throw IllegalStateException("Installer terminato con codice $exit. Ultime righe:\n$details")
        }
    }

    private fun installVanilla(serverDir: File, minecraftVersion: String, onProgress: (String) -> Unit) {
        onProgress("Recupero manifest versioni")
        val manifestUrl = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
        val body = client.newCall(Request.Builder().url(manifestUrl).build()).execute().body?.string()
            ?: throw IllegalStateException("Manifest non raggiungibile")

        val versionUrlRegex = "\"id\"\\s*:\\s*\"$minecraftVersion\"[^}]*\"url\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        val versionUrl = versionUrlRegex.find(body)?.groupValues?.get(1)
            ?: throw IllegalStateException("Versione $minecraftVersion non trovata")

        val versionBody = client.newCall(Request.Builder().url(versionUrl).build()).execute().body?.string()
            ?: throw IllegalStateException("Dettagli versione non disponibili")

        val serverJarRegex = "\"server\"\\s*:\\s*\\{[^}]*\"url\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        val serverJarUrl = serverJarRegex.find(versionBody)?.groupValues?.get(1)
            ?: throw IllegalStateException("URL server.jar non trovato per $minecraftVersion")

        downloadFile(serverJarUrl, File(serverDir, "server.jar"), onProgress)
    }

    private fun installForge(serverDir: File, minecraftVersion: String, forgeVersion: String, onProgress: (String) -> Unit) {
        onProgress("Download Minecraft vanilla (base per Forge)")
        installVanilla(serverDir, minecraftVersion, onProgress)

        val installerName = "forge-$minecraftVersion-$forgeVersion-installer.jar"
        val url = "https://maven.minecraftforge.net/net/minecraftforge/forge/$minecraftVersion-$forgeVersion/$installerName"
        val installerFile = File(serverDir, installerName)

        downloadFile(url, installerFile, onProgress)
        onProgress("Esecuzione installer Forge")
        runJavaJar(serverDir, installerName, listOf("--installServer"), onProgress)
        installerFile.delete()
    }

    private fun installNeoForge(serverDir: File, minecraftVersion: String, neoForgeVersion: String, onProgress: (String) -> Unit) {
        onProgress("Download Minecraft vanilla (base per NeoForge)")
        installVanilla(serverDir, minecraftVersion, onProgress)

        val installerName = "neoforge-$neoForgeVersion-installer.jar"
        val url = "https://maven.neoforged.net/releases/net/neoforged/neoforge/$neoForgeVersion/$installerName"
        val installerFile = File(serverDir, installerName)

        downloadFile(url, installerFile, onProgress)
        onProgress("Esecuzione installer NeoForge")
        runJavaJar(serverDir, installerName, listOf("--installServer"), onProgress)
        installerFile.delete()
    }

    private fun installFabric(serverDir: File, minecraftVersion: String, loaderVersion: String?, onProgress: (String) -> Unit) {
        onProgress("Download Minecraft vanilla (base per Fabric)")
        installVanilla(serverDir, minecraftVersion, onProgress)

        val installerVersion = "1.0.1"
        val installerName = "fabric-installer-$installerVersion.jar"
        val url = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/$installerVersion/$installerName"
        val installerFile = File(serverDir, installerName)

        downloadFile(url, installerFile, onProgress)
        onProgress("Esecuzione installer Fabric")

        val args = mutableListOf("server", "-mcversion", minecraftVersion, "-dir", serverDir.absolutePath)
        if (loaderVersion != null) {
            args.add("-loader")
            args.add(loaderVersion)
        }
        runJavaJar(serverDir, installerName, args, onProgress)
        installerFile.delete()

        val fabricLaunchJar = File(serverDir, "fabric-server-launch.jar")
        if (!fabricLaunchJar.exists()) {
            throw IllegalStateException("Installazione Fabric fallita: fabric-server-launch.jar non trovato")
        }
        onProgress("Fabric installato correttamente, launcher: fabric-server-launch.jar")

        installFabricApi(serverDir, minecraftVersion, onProgress)
    }

    private fun installFabricApi(serverDir: File, minecraftVersion: String, onProgress: (String) -> Unit) {
        onProgress("Download Fabric API (richiesta dalla maggior parte delle mod)")
        try {
            val modrinth = ModrinthApi()
            val versions = modrinth.getVersions("P7dR8mSH", ModLoader.FABRIC, minecraftVersion)
            val bestVersion = versions.firstOrNull()

            if (bestVersion == null) {
                onProgress("Fabric API non disponibile per questa versione, salto (potrebbe non servire o non essere ancora pubblicata)")
                return
            }

            val modsDir = File(serverDir, "mods")
            modsDir.mkdirs()
            modrinth.downloadMod(modsDir, bestVersion) { line -> onProgress(line) }

            val tracker = InstalledModsTracker(serverDir)
            tracker.recordInstall("P7dR8mSH", "Modrinth", bestVersion.filename)

            onProgress("Fabric API installata: ${bestVersion.filename}")
        } catch (e: Exception) {
            onProgress("Impossibile installare Fabric API automaticamente: ${e.message}. Puoi installarla manualmente dal catalogo mod.")
        }
    }

    private fun installQuilt(serverDir: File, minecraftVersion: String, loaderVersion: String?, onProgress: (String) -> Unit) {
        val installerVersion = "0.6.0"
        val installerName = "quilt-installer-$installerVersion.jar"
        val url = "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/$installerVersion/$installerName"
        val installerFile = File(serverDir, installerName)

        downloadFile(url, installerFile, onProgress)
        onProgress("Esecuzione installer Quilt")

        val args = mutableListOf("install", "server", minecraftVersion)
        if (loaderVersion != null) args.add(loaderVersion)
        args.add("--download-server")
        args.add("--install-dir=${serverDir.absolutePath}")

        runJavaJar(serverDir, installerName, args, onProgress)
        installerFile.delete()

        val quiltLaunchJar = File(serverDir, "quilt-server-launch.jar")
        if (!quiltLaunchJar.exists()) {
            throw IllegalStateException("Installazione Quilt fallita: quilt-server-launch.jar non trovato")
        }
        onProgress("Quilt installato correttamente, launcher: quilt-server-launch.jar")
    }
}