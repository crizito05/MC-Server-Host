package com.pcaudio.minecraft_server.core

data class ModSearchResult(
    val projectId: String,
    val title: String,
    val description: String,
    val downloads: Int,
    val iconUrl: String?,
    val sourceProvider: String
)

data class ModVersionFile(
    val filename: String,
    val downloadUrl: String,
    val versionNumber: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val sourceProvider: String,
    val requiredDependencyProjectIds: List<String> = emptyList(),
    val releasedAt: String = ""
)