package com.pcaudio.minecraft_server.core

import java.io.File

class ModCatalog(
    private val modrinth: ModrinthApi,
    private val curseForge: CurseForgeApi?
) {

    fun availableProviders(): List<ModProvider> {
        val list = mutableListOf<ModProvider>(modrinth)
        if (curseForge != null) list.add(curseForge)
        return list
    }

    fun searchAll(query: String, loader: ModLoader, minecraftVersion: String, limit: Int = 20): Map<String, List<ModSearchResult>> {
        val result = mutableMapOf<String, List<ModSearchResult>>()
        for (provider in availableProviders()) {
            result[provider.name] = try {
                provider.search(query, loader, minecraftVersion, limit)
            } catch (e: Exception) {
                emptyList()
            }
        }
        return result
    }

    fun searchProvider(providerName: String, query: String, loader: ModLoader, minecraftVersion: String, limit: Int = 20): List<ModSearchResult> {
        val provider = availableProviders().firstOrNull { it.name == providerName }
            ?: throw IllegalArgumentException("Provider $providerName non disponibile")
        return provider.search(query, loader, minecraftVersion, limit)
    }

    fun getVersions(providerName: String, projectId: String, loader: ModLoader, minecraftVersion: String): List<ModVersionFile> {
        val provider = availableProviders().firstOrNull { it.name == providerName }
            ?: throw IllegalArgumentException("Provider $providerName non disponibile")
        return provider.getVersions(projectId, loader, minecraftVersion)
    }

    fun getDescription(providerName: String, projectId: String): String {
        val provider = availableProviders().firstOrNull { it.name == providerName }
            ?: return "Provider non disponibile"
        return provider.getDescription(projectId)
    }

    fun getEnvironmentInfo(providerName: String, projectId: String): Pair<String, String> {
        val provider = availableProviders().firstOrNull { it.name == providerName }
            ?: return Pair("unknown", "unknown")
        return provider.getEnvironmentInfo(projectId)
    }

    fun downloadMod(providerName: String, modsDir: File, versionFile: ModVersionFile, onProgress: (String) -> Unit) {
        val provider = availableProviders().firstOrNull { it.name == providerName }
            ?: throw IllegalArgumentException("Provider $providerName non disponibile")
        provider.downloadMod(modsDir, versionFile, onProgress)
    }
}