package com.pcaudio.minecraft_server.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class PlayitClaimApi {

    companion object {
        const val API_BASE = "https://api.playit.gg"
        const val CLAIM_SETUP_PATH = "/claim/setup"
        const val CLAIM_EXCHANGE_PATH = "/claim/exchange"
        const val CLAIM_URL_BASE = "https://playit.gg/claim/"
        const val AGENT_TYPE = "assignable"
        const val VERSION_TEXT = "playit 1.0.10"
        const val RESPONSE_SNIPPET_LENGTH = 300
    }

    fun generateCode(): String {
        val bytes = ByteArray(5)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { String.format("%02x", it.toInt() and 0xff) }
    }

    fun claimUrl(code: String): String {
        return CLAIM_URL_BASE + code
    }

    fun claimSetup(code: String): String {
        val body = JSONObject()
            .put("code", code)
            .put("agent_type", AGENT_TYPE)
            .put("version", VERSION_TEXT)
            .toString()
        val root = post(CLAIM_SETUP_PATH, body)
        val status = root.optString("status", "")
        val data = root.opt("data")
        if (status == "success" && data is String && data.isNotBlank()) {
            return normalize(data)
        }
        throw Exception("Attivazione non riuscita: " + describe(root))
    }

    fun claimExchange(code: String): String? {
        val body = JSONObject().put("code", code).toString()
        val root = post(CLAIM_EXCHANGE_PATH, body)
        val status = root.optString("status", "")
        val data = root.opt("data")
        if (status == "success" && data is JSONObject) {
            val secret = data.optString("secret_key", "")
            if (secret.isNotBlank()) {
                return secret
            }
            throw Exception("Risposta senza segreto: " + snippet(root.toString()))
        }
        if (status == "fail" && data is String) {
            val normalized = normalize(data)
            if (normalized == "notaccepted" || normalized == "notsetup") {
                return null
            }
            throw Exception("Attivazione non riuscita: " + data)
        }
        throw Exception("Risposta inattesa dal server playit: " + snippet(root.toString()))
    }

    private fun post(path: String, body: String): JSONObject {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(API_BASE + path)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        var statusCode = -1
        var raw = ""
        try {
            client.newCall(request).execute().use { response ->
                statusCode = response.code
                raw = response.body?.string() ?: ""
            }
        } catch (e: Exception) {
            throw Exception("Contatto con api.playit.gg non riuscito: " + (e.message ?: "errore sconosciuto"))
        }
        if (statusCode < 200 || statusCode > 299) {
            throw Exception("Il server playit ha risposto HTTP " + statusCode + ": " + snippet(raw))
        }
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw Exception("Risposta del server playit non interpretabile: " + snippet(raw))
        }
    }

    private fun normalize(value: String): String {
        return value.lowercase().replace("_", "").replace("-", "").replace(" ", "")
    }

    private fun describe(root: JSONObject): String {
        val data = root.opt("data")
        if (data is String && data.isNotBlank()) {
            return data
        }
        return snippet(root.toString())
    }

    private fun snippet(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.length > RESPONSE_SNIPPET_LENGTH) {
            trimmed.take(RESPONSE_SNIPPET_LENGTH) + "…"
        } else {
            trimmed
        }
    }
}