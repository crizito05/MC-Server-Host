package com.pcaudio.minecraft_server.core

data class ServerConfig(
    val id: String,
    val name: String,
    val loader: ModLoader,
    val minecraftVersion: String,
    val loaderVersion: String?,
    val ramMb: Int,
    val directoryPath: String,
    val usesRoot: Boolean,
    val jdkMajorVersion: Int
)