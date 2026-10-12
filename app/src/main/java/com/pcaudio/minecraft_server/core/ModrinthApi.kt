package com.pcaudio.minecraft_server.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

class ModrinthApi : ModProvider {

    override val name = "Modrinth"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val baseUrl = "https://api.modrinth.com/v2"

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "pcaudio/minecraft_server").build()
        val response = client.newCall(request).execute()
        return response.body?.string() ?: throw IllegalStateException("Nessuna risposta da $url")
    }

    override fun search(query: String, loader: ModLoader, minecraftVersion: String, limit: Int): List<ModSearchResult> {
        val loaderFacet = loader.name.lowercase()
        val facets = "[[\"categories:$loaderFacet\"],[\"versions:$minecraftVersion\"],[\"project_type:mod\"]]"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val encodedFacets = URLEncoder.encode(facets, "UTF-8")
        val sortParam = if (query.isBlank()) "&index=downloads" else ""
        val url = "$baseUrl/search?query=$encodedQuery&facets=$encodedFacets&limit=$limit$sortParam"

        val body = get(url)
        val json = JSONObject(body)
        val hits = json.getJSONArray("hits")
        val result = mutableListOf<ModSearchResult>()

        for (i in 0 until hits.length()) {
            val hit = hits.getJSONObject(i)
            result.add(
                ModSearchResult(
                    projectId = hit.getString("project_id"),
                    title = hit.getString("title"),
                    description = hit.optString("description", ""),
                    downloads = hit.optInt("downloads", 0),
                    iconUrl = if (hit.has("icon_url") && !hit.isNull("icon_url")) hit.getString("icon_url") else null,
                    sourceProvider = name
                )
            )
        }
        return result
    }

    override fun getVersions(projectId: String, loader: ModLoader, minecraftVersion: String): List<ModVersionFile> {
        val url = "$baseUrl/project/$projectId/version"
        val body = get(url)
        val array = JSONArray(body)
        val result = mutableListOf<ModVersionFile>()

        for (i in 0 until array.length()) {
            val version = array.getJSONObject(i)
            val loaders = version.getJSONArray("loaders")
            val gameVersions = version.getJSONArray("game_versions")

            val loaderMatch = (0 until loaders.length()).any {
                loaders.getString(it).equals(loader.name, ignoreCase = true)
            }
            val versionMatch = (0 until gameVersions.length()).any {
                gameVersions.getString(it) == minecraftVersion
            }
            if (!loaderMatch || !versionMatch) continue

            val requiredDeps = mutableListOf<String>()
            val dependenciesArray = version.optJSONArray("dependencies")
            if (dependenciesArray != null) {
                for (d in 0 until dependenciesArray.length()) {
                    val dep = dependenciesArray.getJSONObject(d)
                    val depType = dep.optString("dependency_type", "")
                    val depProjectId = dep.optString("project_id", null)
                    if (depType == "required" && depProjectId != null) {
                        requiredDeps.add(depProjectId)
                    }
                }
            }

            val files = version.getJSONArray("files")
            for (f in 0 until files.length()) {
                val file = files.getJSONObject(f)
                result.add(
                    ModVersionFile(
                        filename = file.getString("filename"),
                        downloadUrl = file.getString("url"),
                        versionNumber = version.getString("version_number"),
                        gameVersions = (0 until gameVersions.length()).map { gameVersions.getString(it) },
                        loaders = (0 until loaders.length()).map { loaders.getString(it) },
                        sourceProvider = name,
                        requiredDependencyProjectIds = requiredDeps,
                        releasedAt = version.optString("date_published", "")
                    )
                )
            }
        }
        return result
    }

    override fun getDescription(projectId: String): String {
        return try {
            val body = get("$baseUrl/project/$projectId")
            val json = JSONObject(body)
            json.optString("body", json.optString("description", "Nessuna descrizione disponibile"))
        } catch (e: Exception) {
            "Descrizione non disponibile"
        }
    }

    override fun getEnvironmentInfo(projectId: String): Pair<String, String> {
        return try {
            val body = get("$baseUrl/project/$projectId")
            val json = JSONObject(body)
            val clientSide = json.optString("client_side", "unknown")
            val serverSide = json.optString("server_side", "unknown")
            Pair(clientSide, serverSide)
        } catch (e: Exception) {
            Pair("unknown", "unknown")
        }
    }

    override fun downloadMod(modsDir: File, versionFile: ModVersionFile, onProgress: (String) -> Unit) {
        onProgress("Download ${versionFile.filename}")
        val request = Request.Builder().url(versionFile.downloadUrl).build()
        val response = client.newCall(request).execute()
        val body = response.body ?: throw IllegalStateException("Download mod fallito")
        val target = File(modsDir, versionFile.filename)
        target.outputStream().use { out -> body.byteStream().copyTo(out) }
    }
}