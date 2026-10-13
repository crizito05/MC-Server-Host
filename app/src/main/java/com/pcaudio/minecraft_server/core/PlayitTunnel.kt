package com.pcaudio.minecraft_server.core

data class PlayitTunnel(
    val displayAddress: String,
    val name: String,
    val tunnelType: String,
    val portType: String,
    val disabledReason: String?
)