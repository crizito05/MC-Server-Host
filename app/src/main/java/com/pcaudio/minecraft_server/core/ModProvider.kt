package com.pcaudio.minecraft_server.core

import java.io.File

interface ModProvider {
    val name: String
    fun search(query: String, loader: ModLoader, minecraftVersion: String, limit: Int): List<ModSearchResult>
    fun getVersions(projectId: String, loader: ModLoader, minecraftVersion: String): List<ModVersionFile>
    fun downloadMod(modsDir: File, versionFile: ModVersionFile, onProgress: (String) -> Unit)
    fun getDescription(projectId: String): String
    fun getEnvironmentInfo(projectId: String): Pair<String, String>
}