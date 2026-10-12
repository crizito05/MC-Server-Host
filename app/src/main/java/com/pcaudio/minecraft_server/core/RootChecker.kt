package com.pcaudio.minecraft_server.core

import java.io.File

class RootChecker {

    fun isDeviceRooted(): Boolean {
        val paths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/su",
            "/vendor/bin/su"
        )
        if (paths.any { File(it).exists() }) return true

        return try {
            val process = ProcessBuilder("which", "su").start()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}