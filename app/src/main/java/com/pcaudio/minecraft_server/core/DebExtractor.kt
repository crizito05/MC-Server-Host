package com.pcaudio.minecraft_server.core

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class DebExtractor {

    fun extractDataTarXz(debFile: File, outputDir: File): File {
        val dataTarXz = File(outputDir, "data.tar.xz")
        FileInputStream(debFile).use { input ->
            skipArHeader(input)
            extractArMember(input, dataTarXz, "data.tar.xz")
        }
        return dataTarXz
    }

    private fun skipArHeader(input: FileInputStream) {
        val magic = ByteArray(8)
        input.read(magic)
    }

    private fun extractArMember(input: FileInputStream, targetIfMatch: File, memberNameWanted: String) {
        while (true) {
            val header = ByteArray(60)
            val bytesRead = input.read(header)
            if (bytesRead < 60) break

            val name = String(header, 0, 16).trim()
            val sizeStr = String(header, 48, 10).trim()
            val size = sizeStr.toLongOrNull() ?: break

            if (name.startsWith(memberNameWanted)) {
                FileOutputStream(targetIfMatch).use { out ->
                    copyExactBytes(input, out, size)
                }
                return
            } else {
                skipExactBytes(input, size)
            }

            if (size % 2 == 1L) input.skip(1)
        }
        throw IllegalStateException("Membro $memberNameWanted non trovato nell'archivio .deb")
    }

    private fun copyExactBytes(input: FileInputStream, output: FileOutputStream, size: Long) {
        val buffer = ByteArray(8192)
        var remaining = size
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipExactBytes(input: FileInputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) break
            remaining -= read
        }
    }

    fun extractTarXz(tarXzFile: File, outputDir: File, stripComponents: Int = 0) {
        XZCompressorInputStream(FileInputStream(tarXzFile)).use { xzIn ->
            TarArchiveInputStream(xzIn).use { tarIn ->
                var entry = tarIn.nextTarEntry
                while (entry != null) {
                    val parts = entry.name.split("/").filter { it.isNotBlank() }
                    if (parts.size > stripComponents) {
                        val strippedPath = parts.drop(stripComponents).joinToString("/")
                        val outFile = File(outputDir, strippedPath)

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out -> tarIn.copyTo(out) }
                            outFile.setExecutable(true)
                            outFile.setReadable(true)
                        }
                    }
                    entry = tarIn.nextTarEntry
                }
            }
        }
    }
}