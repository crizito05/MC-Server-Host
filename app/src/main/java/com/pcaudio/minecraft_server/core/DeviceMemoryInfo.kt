package com.pcaudio.minecraft_server.core

import android.app.ActivityManager
import android.content.Context
import java.io.BufferedReader
import java.io.FileReader

class DeviceMemoryInfo(private val context: Context) {

    fun getTotalRamMb(): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.totalMem / (1024 * 1024)).toInt()
    }

    fun getAvailableRamMb(): Int {
        val fromProc = readMemAvailableFromProc()
        if (fromProc != null) return fromProc

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.availMem / (1024 * 1024)).toInt()
    }

    private fun readMemAvailableFromProc(): Int? {
        return try {
            BufferedReader(FileReader("/proc/meminfo")).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val current = line ?: continue
                    if (current.startsWith("MemAvailable:")) {
                        val kb = current.replace(Regex("[^0-9]"), "").toLongOrNull() ?: return null
                        return (kb / 1024).toInt()
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getRecommendedMaxRamMb(): Int {
        val total = getTotalRamMb()
        return (total * 0.7).toInt()
    }

    fun getSystemReservedMinimumMb(): Int {
        return 800
    }
}