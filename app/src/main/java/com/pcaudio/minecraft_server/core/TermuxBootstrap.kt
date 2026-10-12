package com.pcaudio.minecraft_server.core

import android.content.Context
import org.json.JSONObject
import java.io.File

class TermuxBootstrap(private val context: Context) : JavaRuntimeProvider {

    private val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)

    private val binaryMap: Map<String, String> by lazy {
        val json = context.assets.open("native_binary_map.json").bufferedReader().readText()
        val obj = JSONObject(json)
        val result = mutableMapOf<String, String>()
        obj.keys().forEach { key -> result[key] = obj.getString(key) }
        result
    }

    override fun isInstalled(): Boolean = javaBinary().exists()

    fun resolvedBinary(originalPath: String): File {
        val mappedName = binaryMap[originalPath]
            ?: throw IllegalStateException("Binario $originalPath non trovato nella mappa")
        return File(nativeLibDir, mappedName)
    }

    override fun javaBinary(): File = resolvedBinary("lib/jvm/java-17-openjdk/bin/java")

    override fun libraryDir(): File = nativeLibDir

    override fun ensureRuntimeData(onProgress: (String) -> Unit) {
        homeDir()
        onProgress("Runtime Java (senza root) pronto")
    }

    override fun homeDir(): File = File(context.filesDir, "home").apply { mkdirs() }
}