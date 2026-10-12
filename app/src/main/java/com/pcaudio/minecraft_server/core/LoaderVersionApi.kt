package com.pcaudio.minecraft_server.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class McVersionEntry(val id: String, val type: String)

class LoaderVersionApi {

    private val client = OkHttpClient()

    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        return response.body?.string() ?: throw IllegalStateException("Nessuna risposta da $url")
    }

    fun getMinecraftVersions(includeSnapshots: Boolean = false): List<McVersionEntry> {
        val body = get("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json")
        val json = JSONObject(body)
        val versions = json.getJSONArray("versions")
        val result = mutableListOf<McVersionEntry>()
        for (i in 0 until versions.length()) {
            val entry = versions.getJSONObject(i)
            val type = entry.getString("type")
            if (!includeSnapshots && type != "release") continue
            result.add(McVersionEntry(entry.getString("id"), type))
        }
        return result
    }

    fun getNeoForgeVersions(mcVersion: String): List<String> {
        val body = get("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")
        val shortMc = mcVersion.removePrefix("1.")
        val regex = "<version>($shortMc\\.[0-9]+(?:\\.[0-9]+)?(?:-beta)?)</version>".toRegex()
        val versions = regex.findAll(body).map { it.groupValues[1] }.toList()
        return versions.sortedWith(versionDescendingComparator)
    }

    fun getForgeVersions(mcVersion: String): List<String> {
        val body = get("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml")
        val regex = "<version>($mcVersion-[^<]+)</version>".toRegex()
        val versions = regex.findAll(body).map { it.groupValues[1].substringAfter("$mcVersion-") }.toList()
        return versions.sortedWith(versionDescendingComparator)
    }

    fun getFabricLoaderVersions(mcVersion: String): List<String> {
        val body = get("https://meta.fabricmc.net/v2/versions/loader/$mcVersion")
        val array = JSONArray(body)
        val result = mutableListOf<String>()
        for (i in 0 until array.length()) {
            result.add(array.getJSONObject(i).getJSONObject("loader").getString("version"))
        }
        return result.sortedWith(versionDescendingComparator)
    }

    //fun getQuiltLoaderVersions(mcVersion: String): List<String> {
    //    val body = get("https://meta.quiltmc.org/v3/versions/loader/$mcVersion")
    //    val array = JSONArray(body)
    //    val result = mutableListOf<String>()
    //    for (i in 0 until array.length()) {
    //        result.add(array.getJSONObject(i).getJSONObject("loader").getString("version"))
    //    }
    //    return result.sortedWith(versionDescendingComparator)
    //}

    private fun parseVersionSortKey(version: String): List<Int> {
        return version.replace("-beta", "")
            .split(".")
            .mapNotNull { it.toIntOrNull() }
    }

    private val versionDescendingComparator = Comparator<String> { a, b ->
        val listA = parseVersionSortKey(a)
        val listB = parseVersionSortKey(b)
        val maxSize = maxOf(listA.size, listB.size)
        for (i in 0 until maxSize) {
            val partA = listA.getOrElse(i) { 0 }
            val partB = listB.getOrElse(i) { 0 }
            if (partA != partB) return@Comparator partB - partA
        }
        0
    }
}