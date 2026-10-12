package com.pcaudio.minecraft_server.core

import java.io.File

class ServerPropertiesManager {

    fun readRaw(propertiesFile: File): String {
        if (!propertiesFile.exists()) return ""
        return try {
            propertiesFile.readText()
        } catch (e: Exception) {
            readRawWithRoot(propertiesFile) ?: ""
        }
    }

    fun writeRaw(propertiesFile: File, content: String) {
        try {
            propertiesFile.writeText(content)
        } catch (e: Exception) {
            writeRawWithRoot(propertiesFile, content)
        }
    }

    private fun readRawWithRoot(propertiesFile: File): String? {
        return try {
            val process = ProcessBuilder("su", "-c", "cat \"${propertiesFile.absolutePath}\"").start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            text
        } catch (e: Exception) {
            null
        }
    }

    private fun writeRawWithRoot(propertiesFile: File, content: String) {
        val tempFile = File(propertiesFile.parentFile, "server.properties.tmp")
        tempFile.writeText(content)

        if (!tempFile.exists()) {
            throw java.io.IOException("File temporaneo non creato: ${tempFile.absolutePath}")
        }

        val mvProcess = ProcessBuilder("su", "-c", "mv '${tempFile.absolutePath}' '${propertiesFile.absolutePath}'").start()
        val mvOutput = mvProcess.errorStream.bufferedReader().readText()
        val mvExitCode = mvProcess.waitFor()

        if (mvExitCode != 0) {
            throw java.io.IOException("mv fallito (codice $mvExitCode): $mvOutput")
        }

        val chmodProcess = ProcessBuilder("su", "-c", "chmod 666 '${propertiesFile.absolutePath}'").start()
        val chmodOutput = chmodProcess.errorStream.bufferedReader().readText()
        val chmodExitCode = chmodProcess.waitFor()

        if (chmodExitCode != 0) {
            throw java.io.IOException("chmod fallito (codice $chmodExitCode): $chmodOutput")
        }
    }

    fun getValue(content: String, key: String): String? {
        val regex = Regex("^$key=(.*)$", RegexOption.MULTILINE)
        return regex.find(content)?.groupValues?.get(1)?.trim()
    }

    fun setValue(content: String, key: String, value: String): String {
        val regex = Regex("^$key=.*$", RegexOption.MULTILINE)
        return if (regex.containsMatchIn(content)) {
            regex.replace(content) { "$key=$value" }
        } else {
            val separator = if (content.isNotEmpty() && !content.endsWith("\n")) "\n" else ""
            "$content$separator$key=$value\n"
        }
    }
}