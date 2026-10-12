package com.pcaudio.minecraft_server.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class JdkVersionResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun requiredJdkMajorVersion(minecraftVersion: String): Int {
        return try {
            fetchFromMojangManifest(minecraftVersion) ?: fallbackGuess(minecraftVersion)
        } catch (e: Exception) {
            fallbackGuess(minecraftVersion)
        }
    }

    private fun fetchFromMojangManifest(minecraftVersion: String): Int? {
        val manifestUrl = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
        val manifestBody = client.newCall(Request.Builder().url(manifestUrl).build()).execute().body?.string()
            ?: return null

        val versionUrlRegex = "\"id\"\\s*:\\s*\"$minecraftVersion\"[^}]*\"url\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        val versionUrl = versionUrlRegex.find(manifestBody)?.groupValues?.get(1) ?: return null

        val versionBody = client.newCall(Request.Builder().url(versionUrl).build()).execute().body?.string()
            ?: return null

        val versionJson = JSONObject(versionBody)
        val javaVersionObj = versionJson.optJSONObject("javaVersion") ?: return null
        return javaVersionObj.optInt("majorVersion", -1).takeIf { it > 0 }
    }

    private fun fallbackGuess(minecraftVersion: String): Int {
        val parts = minecraftVersion.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return 21

        val major = parts[0]
        val minor = parts[1]
        val patch = if (parts.size > 2) parts[2] else 0

        return when {
            major > 1 -> 25
            major == 1 && minor < 17 -> throw UnsupportedJdkException(minecraftVersion, 8)
            major == 1 && minor in 17..20 && patch < 5 -> 17
            else -> 21
        }
    }

    companion object {
        fun packageName(jdkMajor: Int): String = "openjdk-$jdkMajor"

        fun packageVersion(jdkMajor: Int): String = when (jdkMajor) {
            8 -> "8.412"
            17 -> "17.0.20"
            21 -> "21.0.12"
            25 -> "25.0.4"
            else -> "21.0.12"
        }

        fun javaHomeDirName(jdkMajor: Int): String = "java-$jdkMajor-openjdk"
    }
}

class UnsupportedJdkException(val minecraftVersion: String, val requiredJdk: Int) :
    Exception("Minecraft $minecraftVersion richiede Java $requiredJdk, non disponibile su questa piattaforma. Scegli una versione 1.17 o successiva.")