package com.pcaudio.minecraft_server.core

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class ApiKeyStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_api_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getCurseForgeKey(): String? = prefs.getString("curseforge_api_key", null)

    fun setCurseForgeKey(key: String) {
        prefs.edit().putString("curseforge_api_key", key).apply()
    }

    fun clearCurseForgeKey() {
        prefs.edit().remove("curseforge_api_key").apply()
    }

    fun hasCurseForgeKey(): Boolean = !getCurseForgeKey().isNullOrBlank()

    fun getPlayitSecret(): String? = prefs.getString("playit_secret", null)

    fun setPlayitSecret(secret: String) {
        prefs.edit().putString("playit_secret", secret).apply()
    }

    fun clearPlayitSecret() {
        prefs.edit().remove("playit_secret").apply()
    }

    fun hasPlayitSecret(): Boolean = !getPlayitSecret().isNullOrBlank()
}