package com.pcaudio.minecraft_server.core

import android.app.ActivityManager
import android.content.Context
import com.pcaudio.minecraft_server.R

data class OptimizationResult(
    val ramFreedMb: Int,
    val newAvailableRamMb: Int,
    val usedRoot: Boolean
)

class RamOptimizer(private val context: Context) {

    private val memoryInfo = DeviceMemoryInfo(context)
    private val rootChecker = RootChecker()

    fun optimize(onProgress: (String) -> Unit): OptimizationResult {
        val beforeMb = memoryInfo.getAvailableRamMb()
        val isRooted = rootChecker.isDeviceRooted()

        onProgress(context.getString(R.string.tech_ram_root_detected, context.getString(if (isRooted) R.string.tech_yes else R.string.tech_no)))

        killBackgroundAppsNonRoot(onProgress)

        if (isRooted) {
            dropCachesRoot(onProgress)
        }

        onProgress(context.getString(R.string.tech_ram_wait))
        Thread.sleep(1500)

        val afterMb = memoryInfo.getAvailableRamMb()
        val freed = (afterMb - beforeMb).coerceAtLeast(0)

        onProgress(context.getString(R.string.tech_ram_freed, freed))

        return OptimizationResult(
            ramFreedMb = freed,
            newAvailableRamMb = afterMb,
            usedRoot = isRooted
        )
    }

    private fun killBackgroundAppsNonRoot(onProgress: (String) -> Unit) {
        onProgress(context.getString(R.string.tech_ram_killing))
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningProcesses = am.runningAppProcesses ?: return

        for (proc in runningProcesses) {
            if (proc.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND) {
                try {
                    am.killBackgroundProcesses(proc.processName)
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun dropCachesRoot(onProgress: (String) -> Unit) {
        onProgress(context.getString(R.string.tech_ram_cache_root))
        try {
            val process = ProcessBuilder("su", "-c", "echo 3 > /proc/sys/vm/drop_caches").start()
            process.waitFor()
        } catch (e: Exception) {
            onProgress(context.getString(R.string.tech_ram_cache_failed, e.message))
        }
    }
}