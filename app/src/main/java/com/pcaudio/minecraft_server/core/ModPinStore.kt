package com.pcaudio.minecraft_server.core

import org.json.JSONObject
import java.io.File

data class PinnedMod(
    val filename: String,
    val versionNumber: String,
    val pinnedAt: Long
)

class ModPinStore(private val serverDir: File) {

    private val pinFile = File(serverDir, "pinned_mods.json")

    fun pin(key: String, filename: String, versionNumber: String) {
        val map = loadMap()
        val entry = JSONObject()
        entry.put("filename", filename)
        entry.put("versionNumber", versionNumber)
        entry.put("pinnedAt", System.currentTimeMillis())
        map.put(key, entry)
        write(map)
    }

    fun unpin(key: String) {
        val map = loadMap()
        map.remove(key)
        write(map)
    }

    fun isPinned(key: String): Boolean {
        val map = loadMap()
        if (!map.has(key)) return false
        val entry = map.optJSONObject(key) ?: return false
        val filename = entry.optString("filename", "")
        if (filename.isBlank()) return true
        return File(serverDir, "mods/$filename").exists()
    }

    fun getPinned(key: String): PinnedMod? {
        val map = loadMap()
        val entry = map.optJSONObject(key) ?: return null
        return PinnedMod(
            filename = entry.optString("filename", ""),
            versionNumber = entry.optString("versionNumber", ""),
            pinnedAt = entry.optLong("pinnedAt", 0L)
        )
    }

    private fun write(map: JSONObject) {
        try {
            pinFile.writeText(map.toString())
        } catch (e: Exception) {
        }
    }

    private fun loadMap(): JSONObject {
        if (!pinFile.exists()) return JSONObject()
        return try {
            JSONObject(pinFile.readText())
        } catch (e: Exception) {
            JSONObject()
        }
    }
}