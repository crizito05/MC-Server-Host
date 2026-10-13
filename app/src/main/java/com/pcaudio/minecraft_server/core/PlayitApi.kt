package com.pcaudio.minecraft_server.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PlayitApi {

    companion object {
        const val API_BASE = "https://api.playit.gg"
        const val LIST_TUNNELS_PATH = "/v1/tunnels/list"
        const val RESPONSE_SNIPPET_LENGTH = 300
    }

    fun listTunnels(secret: String): List<PlayitTunnel> {
        if (secret.isBlank()) {
            throw IllegalArgumentException("Segreto playit mancante")
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(API_BASE + LIST_TUNNELS_PATH)
            .header("Authorization", "Agent-Key " + secret)
            .post("{}".toRequestBody("application/json".toMediaType()))
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
            throw Exception("Il server playit ha risposto HTTP " + statusCode + ": " + describeError(raw))
        }
        return parseTunnels(raw)
    }

    private fun parseTunnels(text: String): List<PlayitTunnel> {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw Exception("Risposta del server playit non interpretabile: " + snippet(text))
        }
        val status = root.optString("status", "")
        if (status == "fail" || status == "error") {
            throw Exception("Il server playit ha risposto con errore: " + describeError(text))
        }
        if (status != "success") {
            val failNode = root.opt("fail")
            val errorNode = root.opt("error")
            if ((failNode != null && failNode !== JSONObject.NULL) || (errorNode != null && errorNode !== JSONObject.NULL)) {
                throw Exception("Il server playit ha risposto con errore: " + describeError(text))
            }
        }
        val items = tunnelItems(root)
            ?: throw Exception("Risposta inattesa dal server playit: " + snippet(text))
        return items.map { toTunnel(it) }
    }

    private fun tunnelItems(root: JSONObject): List<JSONObject>? {
        root.opt("tunnels")?.let { node ->
            if (node !== JSONObject.NULL) {
                collect(node)?.let { return it }
            }
        }
        val data = root.opt("data")
        if (data is JSONObject) {
            data.opt("tunnels")?.let { node ->
                if (node !== JSONObject.NULL) {
                    collect(node)?.let { return it }
                }
            }
            collect(data)?.let { return it }
        } else if (data is JSONArray) {
            collect(data)?.let { return it }
        }
        val success = root.opt("success")
        if (success != null && success !== JSONObject.NULL) {
            if (success is JSONObject) {
                success.opt("tunnels")?.let { node ->
                    if (node !== JSONObject.NULL) {
                        collect(node)?.let { return it }
                    }
                }
            }
            collect(success)?.let { return it }
        }
        return null
    }

    private fun collect(node: Any): List<JSONObject>? {
        val result = mutableListOf<JSONObject>()
        when (node) {
            is JSONArray -> for (index in 0 until node.length()) {
                val item = node.opt(index)
                if (item is JSONObject) {
                    result.add(item)
                }
            }
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val item = node.opt(keys.next())
                    if (item is JSONObject) {
                        result.add(item)
                    }
                }
            }
            else -> return null
        }
        return result
    }

    private fun toTunnel(item: JSONObject): PlayitTunnel {
        val disabled = disabledReason(item)
        return PlayitTunnel(
            displayAddress = displayAddress(item),
            name = firstString(item, "name", "tunnel_name", "tunnelName"),
            tunnelType = firstString(item, "tunnel_type", "tunnelType", "tunnel_type_display", "tunnelTypeDisplay"),
            portType = firstString(item, "port_type", "portType"),
            disabledReason = disabled
        )
    }

    private fun displayAddress(item: JSONObject): String {
        val candidates = mutableListOf<String>()
        val connect = item.opt("connect_addresses")
        if (connect is JSONArray) {
            val entries = mutableListOf<Pair<String, JSONObject>>()
            for (index in 0 until connect.length()) {
                val entry = connect.opt(index)
                if (entry is JSONObject) {
                    val value = entry.opt("value")
                    if (value is JSONObject) {
                        entries.add(Pair(entry.optString("type", ""), value))
                    }
                }
            }
            for (wanted in listOf("auto", "domain", "addr4", "addr6", "ip4", "ip6")) {
                for (entry in entries) {
                    if (entry.first != wanted) {
                        continue
                    }
                    if (wanted == "ip4" || wanted == "ip6") {
                        val host = entry.second.optString("address", "")
                        val port = entry.second.optInt("default_port", 0)
                        if (host.isNotBlank() && port > 0) {
                            candidates.add(host + ":" + port)
                        }
                    } else {
                        val address = entry.second.optString("address", "")
                        if (address.isNotBlank()) {
                            candidates.add(address)
                        }
                        val domain = entry.second.optString("domain", "")
                        if (domain.isNotBlank()) {
                            candidates.add(domain)
                        }
                    }
                }
            }
        }
        val publics = item.opt("public_allocations")
        if (publics is JSONArray) {
            for (index in 0 until publics.length()) {
                val entry = publics.opt(index)
                if (entry is JSONObject) {
                    val details = entry.opt("details")
                    if (details is JSONObject) {
                        val host = details.optString("auto_domain", "").ifBlank { details.optString("ip_hostname", "") }
                        val port = details.optInt("port", 0)
                        if (host.isNotBlank() && port > 0) {
                            candidates.add(host + ":" + port)
                        }
                    }
                }
            }
        }
        for (candidate in candidates) {
            if (candidate.isBlank()) {
                continue
            }
            if (candidate.contains(":")) {
                return candidate
            }
            val port = allocationPort(item)
            return if (port > 0) candidate + ":" + port else candidate
        }
        return firstString(item, "display_address", "displayAddress", "address")
    }

    private fun allocationPort(item: JSONObject): Int {
        val publics = item.opt("public_allocations")
        if (publics is JSONArray) {
            for (index in 0 until publics.length()) {
                val entry = publics.opt(index)
                if (entry is JSONObject) {
                    val details = entry.opt("details")
                    if (details is JSONObject) {
                        val port = details.optInt("port", 0)
                        if (port > 0) {
                            return port
                        }
                    }
                }
            }
        }
        return 0
    }

    private fun disabledReason(item: JSONObject): String? {
        val reasons = mutableListOf<String>()
        val offline = item.opt("offline_reasons")
        if (offline is JSONArray) {
            for (index in 0 until offline.length()) {
                val raw = offline.optString(index, "")
                if (raw.isNotBlank()) {
                    reasons.add(translateOfflineReason(raw))
                }
            }
        }
        if (reasons.isNotEmpty()) {
            return reasons.joinToString(", ")
        }
        val userEnabled = item.opt("user_enabled")
        if (userEnabled is Boolean && !userEnabled) {
            return "disattivato"
        }
        return null
    }

    private fun translateOfflineReason(raw: String): String {
        return when (raw) {
            "OriginNotSet" -> "origine non impostata"
            "AgentDisabled" -> "agente disattivato"
            "AgentOverLimit" -> "superato il limite di agenti"
            "TunnelDisabled" -> "tunnel disattivato"
            "PublicAllocationMissing" -> "indirizzo pubblico mancante"
            "PublicAllocationPending" -> "indirizzo pubblico in attesa"
            else -> raw
        }
    }

    private fun firstString(item: JSONObject, vararg names: String): String {
        for (name in names) {
            val value = item.opt(name) ?: continue
            if (value === JSONObject.NULL) {
                continue
            }
            if (value is String && value.isNotBlank()) {
                return value
            }
            if (value is JSONObject) {
                for (inner in listOf("address", "value", "display_address", "name", "message")) {
                    val innerValue = value.optString(inner, "")
                    if (innerValue.isNotBlank()) {
                        return innerValue
                    }
                }
            }
        }
        return ""
    }

    private fun describeError(text: String): String {
        return try {
            val root = JSONObject(text)
            val status = root.optString("status", "")
            if (status == "fail" || status == "error") {
                val data = root.opt("data")
                if (data is String && data.isNotBlank()) {
                    return data
                }
                if (data is JSONObject) {
                    val type = data.optString("type", "")
                    val message = data.opt("message")
                    if (message is String && message.isNotBlank()) {
                        return if (type.isNotBlank()) type + ": " + message else message
                    }
                    if (message is JSONObject) {
                        val inner = message.optString("message", "").ifBlank { message.toString() }
                        return if (type.isNotBlank()) type + ": " + inner else inner
                    }
                    if (type.isNotBlank()) {
                        return type
                    }
                }
                return snippet(text)
            }
            for (key in listOf("error", "fail", "message", "msg")) {
                val value = root.opt(key) ?: continue
                if (value === JSONObject.NULL) {
                    continue
                }
                if (value is String && value.isNotBlank()) {
                    return value
                }
                if (value is JSONObject) {
                    for (inner in listOf("message", "msg", "error", "reason")) {
                        val innerValue = value.optString(inner, "")
                        if (innerValue.isNotBlank()) {
                            return innerValue
                        }
                    }
                    return value.toString()
                }
                return value.toString()
            }
            snippet(text)
        } catch (e: Exception) {
            snippet(text)
        }
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