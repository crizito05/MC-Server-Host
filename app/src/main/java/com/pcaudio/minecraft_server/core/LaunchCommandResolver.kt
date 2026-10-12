package com.pcaudio.minecraft_server.core

import java.io.File

class LaunchCommandResolver {

    fun resolve(serverDir: File, javaBin: File, ramMb: Int): List<String> {
        val fabricLaunchJar = File(serverDir, "fabric-server-launch.jar")
        //val quiltLaunchJar = File(serverDir, "quilt-server-launch.jar")
        val simpleJar = File(serverDir, "server.jar")
        val librariesDir = File(serverDir, "libraries")
        val unixArgsFile = librariesDir.walkTopDown()
            .firstOrNull { it.name == "unix_args.txt" }
            ?: librariesDir.walkTopDown().firstOrNull { it.name.endsWith("_args.txt") && !it.name.startsWith("win_") }

        return when {
            fabricLaunchJar.exists() -> listOf(
                javaBin.absolutePath,
                "-Xmx${ramMb}M",
                "-Xms${ramMb / 2}M",
                "-jar",
                fabricLaunchJar.absolutePath,
                "nogui"
            )
            //quiltLaunchJar.exists() -> listOf(
            //    javaBin.absolutePath,
            //    "-Xmx${ramMb}M",
            //    "-Xms${ramMb / 2}M",
            //    "-jar",
            //    quiltLaunchJar.absolutePath,
            //    "nogui"
            //)
            unixArgsFile != null && unixArgsFile.exists() -> listOf(
                javaBin.absolutePath,
                "-Xmx${ramMb}M",
                "-Xms${ramMb / 2}M",
                "@${unixArgsFile.absolutePath}",
                "nogui"
            )
            simpleJar.exists() -> listOf(
                javaBin.absolutePath,
                "-Xmx${ramMb}M",
                "-Xms${ramMb / 2}M",
                "-jar",
                simpleJar.absolutePath,
                "nogui"
            )
            else -> throw IllegalStateException("Nessun eseguibile server trovato in $serverDir")
        }
    }
}