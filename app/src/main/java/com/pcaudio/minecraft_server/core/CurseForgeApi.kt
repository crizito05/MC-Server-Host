package com.pcaudio.minecraft_server.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CurseForgeApi(private val apiKey: String) : ModProvider {

    override val name = "CurseForge"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val baseUrl = "https://api.curseforge.com/v1"
    private val minecraftGameId = 432

    private val loaderTypeMap = mapOf(
        ModLoader.FORGE to 1,
        ModLoader.FABRIC to 4,
        //ModLoader.QUILT to 5,
        ModLoader.NEOFORGE to 6
    )

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("CurseForge API errore ${response.code}: verifica la API key")
        }
        return response.body?.string() ?: throw IllegalStateException("Nessuna risposta da $url")
    }

    override fun search(query: String, loader: ModLoader, minecraftVersion: String, limit: Int): List<ModSearchResult> {
        val modLoaderType = loaderTypeMap[loader]
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val sortField = if (query.isBlank()) "6" else "2"
        var url = "$baseUrl/mods/search?gameId=$minecraftGameId&searchFilter=$encodedQuery" +
                "&gameVersion=$minecraftVersion&pageSize=$limit&sortField=$sortField&sortOrder=desc"
        if (modLoaderType != null) url += "&modLoaderType=$modLoaderType"

        val body = get(url)
        val json = JSONObject(body)
        val data = json.getJSONArray("data")
        val result = mutableListOf<ModSearchResult>()

        for (i in 0 until data.length()) {
            val mod = data.getJSONObject(i)
            val logo = mod.optJSONObject("logo")
            result.add(
                ModSearchResult(
                    projectId = mod.getInt("id").toString(),
                    title = mod.getString("name"),
                    description = mod.optString("summary", ""),
                    downloads = mod.optLong("downloadCount", 0L).toInt(),
                    iconUrl = if (logo != null && logo.has("thumbnailUrl") && !logo.isNull("thumbnailUrl")) logo.getString("thumbnailUrl") else null,
                    sourceProvider = name
                )
            )
        }
        return result
    }

    override fun getVersions(projectId: String, loader: ModLoader, minecraftVersion: String): List<ModVersionFile> {
        val modLoaderType = loaderTypeMap[loader]
        var url = "$baseUrl/mods/$projectId/files?gameVersion=$minecraftVersion"
        if (modLoaderType != null) url += "&modLoaderType=$modLoaderType"

        val body = get(url)
        val json = JSONObject(body)
        val data = json.getJSONArray("data")
        val result = mutableListOf<ModVersionFile>()

        for (i in 0 until data.length()) {
            val file = data.getJSONObject(i)
            val gameVersionsArray = file.getJSONArray("gameVersions")
            val gameVersions = (0 until gameVersionsArray.length()).map { gameVersionsArray.getString(it) }

            result.add(
                ModVersionFile(
                    filename = file.getString("fileName"),
                    downloadUrl = file.optString("downloadUrl", ""),
                    versionNumber = file.optString("displayName", file.getString("fileName")),
                    gameVersions = gameVersions,
                    loaders = listOf(loader.name),
                    sourceProvider = name,
                    releasedAt = file.optString("fileDate", "")
                )
            )
        }
        return result.filter { it.downloadUrl.isNotBlank() }
            .sortedWith(compareByDescending { it.releasedAt })
    }

    override fun getDescription(projectId: String): String {
        return try {
            val body = get("$baseUrl/mods/$projectId/description")
            val json = JSONObject(body)
            json.optString("data", "Nessuna descrizione disponibile").replace(Regex("<[^>]*>"), "")
        } catch (e: Exception) {
            "Descrizione non disponibile"
        }
    }

    override fun getEnvironmentInfo(projectId: String): Pair<String, String> {
        return Pair("unknown", "unknown")
    }

    override fun downloadMod(modsDir: File, versionFile: ModVersionFile, onProgress: (String) -> Unit) {
        onProgress("Download ${versionFile.filename}")
        val request = Request.Builder()
            .url(versionFile.downloadUrl)
            .header("x-api-key", apiKey)
            .build()
        val response = client.newCall(request).execute()
        val body = response.body ?: throw IllegalStateException("Download mod fallito")
        val target = File(modsDir, versionFile.filename)
        target.outputStream().use { out -> body.byteStream().copyTo(out) }
    }
}