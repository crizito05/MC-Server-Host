package com.pcaudio.minecraft_server.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ServerRepository(private val context: Context) {

    private val storeFile = File(context.filesDir, "servers.json")

    fun listServers(): List<ServerConfig> {
        if (!storeFile.exists()) return emptyList()
        val array = JSONArray(storeFile.readText())
        val result = mutableListOf<ServerConfig>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)

            val loader = try {
                ModLoader.valueOf(obj.getString("loader"))
            } catch (e: IllegalArgumentException) {
                continue
            }

            result.add(
                ServerConfig(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    loader = loader,
                    minecraftVersion = obj.getString("minecraftVersion"),
                    loaderVersion = if (obj.has("loaderVersion") && !obj.isNull("loaderVersion")) obj.getString("loaderVersion") else null,
                    ramMb = obj.getInt("ramMb"),
                    directoryPath = obj.getString("directoryPath"),
                    usesRoot = obj.optBoolean("usesRoot", false),
                    jdkMajorVersion = obj.optInt("jdkMajorVersion", 21)
                )
            )
        }
        return result
    }

    fun saveServer(config: ServerConfig) {
        val servers = listServers().toMutableList()
        servers.removeAll { it.id == config.id }
        servers.add(config)
        persist(servers)
    }

    fun deleteServer(id: String) {
        val servers = listServers().filterNot { it.id == id }
        persist(servers)
    }

    fun newServerDirectory(name: String): File {
        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        var candidate = File(context.filesDir, "servers/$safeName")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(context.filesDir, "servers/${safeName}_$counter")
            counter++
        }
        return candidate
    }

    fun deleteServerCompletely(id: String): Boolean {
        val config = listServers().firstOrNull { it.id == id } ?: return false
        val serverDir = File(config.directoryPath)
        var deleted = true
        if (serverDir.exists()) {
            deleted = serverDir.deleteRecursively()
            if (serverDir.exists()) {
                deleted = false
            }
        }
        deleteServer(id)
        return deleted
    }

    fun deleteDirectoryRobust(dir: File): Boolean {
        if (!dir.exists()) return true
        try {
            dir.deleteRecursively()
        } catch (e: Exception) {
        }
        if (!dir.exists()) return true
        try {
            val process = ProcessBuilder("su", "-c", "rm -rf '${dir.absolutePath}'").start()
            process.waitFor()
        } catch (e: Exception) {
        }
        return !dir.exists()
    }

    fun generateId(): String = UUID.randomUUID().toString()

    private fun persist(servers: List<ServerConfig>) {
        val array = JSONArray()
        for (server in servers) {
            val obj = JSONObject()
            obj.put("id", server.id)
            obj.put("name", server.name)
            obj.put("loader", server.loader.name)
            obj.put("minecraftVersion", server.minecraftVersion)
            obj.put("loaderVersion", server.loaderVersion)
            obj.put("ramMb", server.ramMb)
            obj.put("directoryPath", server.directoryPath)
            obj.put("usesRoot", server.usesRoot)
            obj.put("jdkMajorVersion", server.jdkMajorVersion)
            array.put(obj)
        }
        storeFile.writeText(array.toString())
    }
}