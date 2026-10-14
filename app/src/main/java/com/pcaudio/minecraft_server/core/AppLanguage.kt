package com.pcaudio.minecraft_server.core

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object AppLanguage {

    const val TAG_ENGLISH = "en"
    const val TAG_ITALIAN = "it"

    fun effectiveTag(): String {
        val stored = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (stored.isNotBlank()) {
            return stored.split(",").first().trim()
        }
        return Locale.getDefault().language
    }

    fun isEnglish(): Boolean {
        return effectiveTag().startsWith(TAG_ENGLISH)
    }

    fun toggle() {
        set(if (isEnglish()) TAG_ITALIAN else TAG_ENGLISH)
    }

    fun set(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
}