package com.pcaudio.minecraft_server.core

import java.io.File

data class ModUpdateEntry(
    val file: File,
    val sourceProvider: String?,
    val projectId: String?,
    val status: String,
    val newest: ModVersionFile?,
    val locked: Boolean
)

class ModUpdateChecker {

    companion object {
        const val STATUS_UPDATE_AVAILABLE = "UPDATE_AVAILABLE"
        const val STATUS_UP_TO_DATE = "UP_TO_DATE"
        const val STATUS_BLOCKED = "BLOCKED"
        const val STATUS_UNKNOWN = "UNKNOWN"
        const val PART_SUFFIX = ".part"
    }

    fun check(
        files: List<File>,
        tracker: InstalledModsTracker,
        pinStore: ModPinStore,
        catalog: ModCatalog,
        loader: ModLoader,
        minecraftVersion: String
    ): List<ModUpdateEntry> {

        val usable = files.filter { it.name.endsWith(".jar") && !it.name.endsWith(PART_SUFFIX) }
        val tracked = tracker.listTracked()
        val versionCache = mutableMapOf<String, List<ModVersionFile>?>()
        val result = mutableListOf<ModUpdateEntry>()

        for (file in usable) {
            val record = tracked.firstOrNull { it.filename == file.name }
            if (record == null) {
                result.add(ModUpdateEntry(file, null, null, STATUS_UNKNOWN, null, false))
                continue
            }

            val key = "${record.sourceProvider}:${record.projectId}"
            val pinned = pinStore.isPinned(key)
            val cacheKey = "${record.sourceProvider}|${record.projectId}"

            if (!versionCache.containsKey(cacheKey)) {
                versionCache[cacheKey] = try {
                    catalog.getVersions(record.sourceProvider, record.projectId, loader, minecraftVersion)
                } catch (e: Exception) {
                    null
                }
            }
            val versions = versionCache[cacheKey]

            if (versions == null) {
                result.add(ModUpdateEntry(file, record.sourceProvider, record.projectId, STATUS_UNKNOWN, null, pinned))
                continue
            }

            val newest = versions.maxByOrNull { it.releasedAt }
            if (newest == null) {
                result.add(ModUpdateEntry(file, record.sourceProvider, record.projectId, STATUS_UNKNOWN, null, pinned))
                continue
            }

            if (newest.filename == file.name) {
                result.add(ModUpdateEntry(file, record.sourceProvider, record.projectId, STATUS_UP_TO_DATE, newest, pinned))
                continue
            }

            if (versions.none { it.filename == file.name }) {
                result.add(ModUpdateEntry(file, record.sourceProvider, record.projectId, STATUS_UNKNOWN, newest, pinned))
                continue
            }

            if (pinned) {
                result.add(ModUpdateEntry(file, record.sourceProvider, record.projectId, STATUS_BLOCKED, newest, true))
                continue
            }

            result.add(ModUpdateEntry(file, record.sourceProvider, record.projectId, STATUS_UPDATE_AVAILABLE, newest, false))
        }

        return result.sortedBy { it.file.name }
    }
}