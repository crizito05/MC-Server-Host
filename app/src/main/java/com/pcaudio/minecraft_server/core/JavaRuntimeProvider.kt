package com.pcaudio.minecraft_server.core

import java.io.File

interface JavaRuntimeProvider {
    fun isInstalled(): Boolean
    fun javaBinary(): File
    fun libraryDir(): File
    fun homeDir(): File
    fun ensureRuntimeData(onProgress: (String) -> Unit)
}