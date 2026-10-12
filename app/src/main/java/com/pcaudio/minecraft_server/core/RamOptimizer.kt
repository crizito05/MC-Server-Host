package com.pcaudio.minecraft_server.core

import android.app.ActivityManager
import android.content.Context

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

        onProgress("Rilevamento root: ${if (isRooted) "sì" else "no"}")

        killBackgroundAppsNonRoot(onProgress)

        if (isRooted) {
            dropCachesRoot(onProgress)
        }

        onProgress("Attesa stabilizzazione memoria")
        Thread.sleep(1500)

        val afterMb = memoryInfo.getAvailableRamMb()
        val freed = (afterMb - beforeMb).coerceAtLeast(0)

        onProgress("RAM liberata: ${freed}MB")

        return OptimizationResult(
            ramFreedMb = freed,
            newAvailableRamMb = afterMb,
            usedRoot = isRooted
        )
    }

    private fun killBackgroundAppsNonRoot(onProgress: (String) -> Unit) {
        onProgress("Chiusura app in background")
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
        onProgress("Pulizia cache di sistema (root)")
        try {
            val process = ProcessBuilder("su", "-c", "echo 3 > /proc/sys/vm/drop_caches").start()
            process.waitFor()
        } catch (e: Exception) {
            onProgress("Pulizia cache root fallita: ${e.message}")
        }
    }
}