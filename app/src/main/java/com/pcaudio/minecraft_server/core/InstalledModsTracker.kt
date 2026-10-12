package com.pcaudio.minecraft_server.core

import org.json.JSONObject
import java.io.File

data class TrackedMod(
    val sourceProvider: String,
    val projectId: String,
    val filename: String
)

class InstalledModsTracker(private val serverDir: File) {

    private val trackerFile = File(serverDir, "installed_mods.json")

    fun recordInstall(projectId: String, sourceProvider: String, filename: String) {
        val map = loadMap()
        map.put("$sourceProvider:$projectId", filename)
        trackerFile.writeText(map.toString())
    }

    fun isInstalled(projectId: String, sourceProvider: String): Boolean {
        val map = loadMap()
        val filename = map.optString("$sourceProvider:$projectId", null) ?: return false
        return File(serverDir, "mods/$filename").exists()
    }

    fun listTracked(): List<TrackedMod> {
        val map = loadMap()
        val result = mutableListOf<TrackedMod>()
        val keys = map.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val separator = key.indexOf(':')
            if (separator <= 0) continue
            result.add(
                TrackedMod(
                    sourceProvider = key.substring(0, separator),
                    projectId = key.substring(separator + 1),
                    filename = map.optString(key, "")
                )
            )
        }
        return result
    }

    //fun isInstalledHeuristic(modTitle: String, serverDir: File): Boolean {
    //    val modsDir = File(serverDir, "mods")
    //    if (!modsDir.exists()) return false
    //    val slug = modTitle.lowercase().replace(Regex("[^a-z0-9]"), "")
    //    return modsDir.listFiles()?.any {
    //        it.name.lowercase().replace(Regex("[^a-z0-9]"), "").contains(slug.take(10))
    //    } ?: false
    //}

    fun removeRecord(projectId: String, sourceProvider: String) {
        val map = loadMap()
        map.remove("$sourceProvider:$projectId")
        trackerFile.writeText(map.toString())
    }

    private fun loadMap(): JSONObject {
        if (!trackerFile.exists()) return JSONObject()
        return try {
            JSONObject(trackerFile.readText())
        } catch (e: Exception) {
            JSONObject()
        }
    }
}