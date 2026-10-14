package com.pcaudio.minecraft_server.core

import android.content.Context
import android.os.Build
import com.pcaudio.minecraft_server.R
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class RootTermuxBootstrap(private val context: Context, private val jdkMajor: Int = 21) : JavaRuntimeProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val prefixDir = File(context.filesDir, "usr")

    override fun isInstalled(): Boolean {
        val javaLibDir = File(prefixDir, "lib/jvm/${JdkVersionResolver.javaHomeDirName(jdkMajor)}/lib")
        return javaBinary().exists() &&
                File(javaLibDir, "libz.so.1").exists() &&
                File(javaLibDir, "libandroid-shmem.so").exists() &&
                File(javaLibDir, "libandroid-spawn.so").exists()
    }

    override fun javaBinary(): File = File(prefixDir, "lib/jvm/${JdkVersionResolver.javaHomeDirName(jdkMajor)}/bin/java")

    override fun libraryDir(): File = File(prefixDir, "lib/jvm/${JdkVersionResolver.javaHomeDirName(jdkMajor)}/lib")

    override fun homeDir(): File = File(context.filesDir, "home").apply { mkdirs() }

    override fun ensureRuntimeData(onProgress: (String) -> Unit) {
        if (isInstalled()) {
            onProgress(context.getString(R.string.tech_runtime_present))
            return
        }
        remountExec(onProgress)
        val debFile = downloadBootstrap(onProgress)
        extractAndInstall(debFile, onProgress)
        installExtraLibrary("zlib", "1.3.2", "z", onProgress)
        installExtraLibrary("libandroid-shmem", "0.7", "liba", onProgress)
        installExtraLibrary("libandroid-spawn", "0.3", "liba", onProgress)
        remountExec(onProgress)
        fixPermissions(onProgress)
    }

    private fun runAsRoot(command: String): Int {
        val process = ProcessBuilder("su", "-c", command).start()
        return process.waitFor()
    }

    fun remountExec(onProgress: (String) -> Unit) {
        onProgress(context.getString(R.string.tech_remount))
        try {
            val dataPath = context.filesDir.absolutePath
            runAsRoot("mount -o remount,exec \"$dataPath\"")
        } catch (e: Exception) {
            onProgress(context.getString(R.string.tech_remount_failed, e.message))
        }
    }

    private fun currentArch(): String {
        val abi = Build.SUPPORTED_ABIS[0]
        return when (abi) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "i686"
            else -> throw IllegalStateException(context.getString(R.string.tech_abi_unsupported, abi))
        }
    }

    private fun bootstrapUrl(): String {
        val pkgName = JdkVersionResolver.packageName(jdkMajor)
        val pkgVersion = JdkVersionResolver.packageVersion(jdkMajor)
        return "https://packages.termux.dev/apt/termux-main/pool/main/o/$pkgName/${pkgName}_${pkgVersion}_${currentArch()}.deb"
    }

    private fun downloadBootstrap(onProgress: (String) -> Unit): File {
        onProgress(context.getString(R.string.tech_download_java))
        val request = Request.Builder().url(bootstrapUrl()).build()
        val response = client.newCall(request).execute()
        val body = response.body ?: throw IllegalStateException(context.getString(R.string.tech_download_failed))

        val totalBytes = body.contentLength()
        val debFile = File(context.cacheDir, "openjdk.deb")

        var downloadedBytes = 0L
        var lastReportedPercent = -1

        body.byteStream().use { input ->
            debFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    if (totalBytes > 0) {
                        val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                        if (percent != lastReportedPercent && percent % 5 == 0) {
                            lastReportedPercent = percent
                            val mbDownloaded = downloadedBytes / (1024 * 1024)
                            val mbTotal = totalBytes / (1024 * 1024)
                            onProgress(context.getString(R.string.tech_download_progress, percent, mbDownloaded, mbTotal))
                        }
                    }
                }
            }
        }

        onProgress(context.getString(R.string.tech_download_done, downloadedBytes / (1024 * 1024)))

        if (downloadedBytes < 1024 * 1024) {
            throw IllegalStateException(context.getString(R.string.tech_download_suspicious, downloadedBytes, bootstrapUrl()))
        }

        return debFile
    }

    private fun extractAndInstall(debFile: File, onProgress: (String) -> Unit) {
        onProgress(context.getString(R.string.tech_extract_package))
        prefixDir.mkdirs()

        val extractor = DebExtractor()
        val workDir = context.cacheDir

        val dataTarXz = extractor.extractDataTarXz(debFile, workDir)
        onProgress(context.getString(R.string.tech_extract_data))

        extractor.extractTarXz(
            tarXzFile = dataTarXz,
            outputDir = prefixDir,
            stripComponents = 6
        )

        dataTarXz.delete()
        onProgress(context.getString(R.string.tech_jdk_extracted, prefixDir.absolutePath))
    }

    private fun installExtraLibrary(packageName: String, version: String, poolPrefix: String, onProgress: (String) -> Unit) {
        val javaLibDir = File(prefixDir, "lib/jvm/${JdkVersionResolver.javaHomeDirName(jdkMajor)}/lib")

        onProgress(context.getString(R.string.tech_download_named, packageName))
        val url = "https://packages.termux.dev/apt/termux-main/pool/main/$poolPrefix/$packageName/${packageName}_${version}_${currentArch()}.deb"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.body ?: throw IllegalStateException(context.getString(R.string.tech_download_named_failed, packageName))

        val debFile = File(context.cacheDir, "$packageName.deb")
        var downloadedBytes = 0L
        body.byteStream().use { input ->
            debFile.outputStream().use { out ->
                downloadedBytes = input.copyTo(out)
            }
        }

        if (downloadedBytes < 1024) {
            throw IllegalStateException(context.getString(R.string.tech_download_suspicious_named, packageName, downloadedBytes, url))
        }

        val extractor = DebExtractor()
        val dataTarXz = extractor.extractDataTarXz(debFile, context.cacheDir)

        val extractDir = File(context.cacheDir, "${packageName}_extracted")
        extractDir.deleteRecursively()
        extractDir.mkdirs()
        extractor.extractTarXz(tarXzFile = dataTarXz, outputDir = extractDir, stripComponents = 0)

        val libraryFiles = extractDir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("lib") && it.name.contains(".so") && it.length() > 0 }
            .toList()

        javaLibDir.mkdirs()
        for (libFile in libraryFiles) {
            libFile.copyTo(File(javaLibDir, libFile.name), overwrite = true)
            File(javaLibDir, libFile.name).setExecutable(true)
            onProgress(context.getString(R.string.tech_installed, libFile.name, libFile.length()))

            for (variantName in generateVersionVariants(libFile.name)) {
                if (variantName != libFile.name) {
                    libFile.copyTo(File(javaLibDir, variantName), overwrite = true)
                    File(javaLibDir, variantName).setExecutable(true)
                }
            }
        }

        if (libraryFiles.isEmpty()) {
            onProgress(context.getString(R.string.tech_no_library, packageName))
        }

        debFile.delete()
        dataTarXz.delete()
        extractDir.deleteRecursively()
    }

    private fun generateVersionVariants(fileName: String): List<String> {
        val soIndex = fileName.indexOf(".so")
        if (soIndex == -1) return listOf(fileName)

        val baseName = fileName.substring(0, soIndex + 3)
        val versionSuffix = fileName.substring(soIndex + 3)
        if (versionSuffix.isEmpty()) return listOf(fileName)

        val versionParts = versionSuffix.removePrefix(".").split(".")
        val variants = mutableListOf(baseName)

        var accumulated = baseName
        for (part in versionParts) {
            accumulated = "$accumulated.$part"
            variants.add(accumulated)
        }

        return variants
    }

    private fun fixPermissions(onProgress: (String) -> Unit) {
        onProgress(context.getString(R.string.tech_permissions))
        runAsRoot("chmod -R 755 ${prefixDir.absolutePath}")
    }
}