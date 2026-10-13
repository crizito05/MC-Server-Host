package com.pcaudio.minecraft_server.core

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class PlayitAgentManager(
    private val context: Context,
    private val log: (String) -> Unit = { line ->
        try {
            val file = File(context.filesDir, "playit/agent_console.log")
            file.parentFile?.mkdirs()
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(Date())
            FileWriter(file, true).use { writer -> writer.write(stamp + " " + line + "\n") }
        } catch (e: Exception) {
            Log.w("PlayitAgentManager", "Scrittura log: " + e.message)
        }
    }
) {

    companion object {
        const val REDIRECT_PORT = 5354
        const val ROOT_COMMAND_TIMEOUT_SECONDS = 10L
        const val PROCESS_STOP_TIMEOUT_SECONDS = 5L
        const val DNS_FALLBACK_PRIMARY = "1.1.1.1"
        const val DNS_FALLBACK_SECONDARY = "8.8.8.8"
    }

    private val agentDir = File(context.filesDir, "playit")
    private val binaryFile = File(context.applicationInfo.nativeLibraryDir, "libplayitd.so")
    private val secretFile = File(agentDir, "playit.toml")
    private val socketFile = File(agentDir, "playitd.sock")
    private val agentLogFile = File(agentDir, "playitd.log")
    private val consoleFile = File(agentDir, "agent_console.log")
    private val uid = android.os.Process.myUid()
    private var process: Process? = null
    private var dnsRelay: PlayitDnsRelay? = null
    private var redirectRuleActive = false

    fun isRunning(): Boolean = process?.isAlive == true

    fun start(secret: String = storedSecret(), onUnexpectedExit: () -> Unit = {}): Boolean {
        val problem = secretProblem(secret)
        if (problem != null) {
            log(problem)
            return false
        }
        if (!binaryFile.isFile) {
            log("libplayitd.so non è presente nell'installazione: esegui Clean e Rebuild, poi reinstalla l'APK")
            return false
        }
        stop()
        killOrphanAgents()
        if (!clearStaleAgentFiles()) {
            return false
        }
        if (!writeSecretFile(secret)) {
            return false
        }
        val resolvers = resolveResolvers()
        val relay = PlayitDnsRelay(resolvers, log)
        if (!relay.start()) {
            return false
        }
        dnsRelay = relay
        if (!installRedirectRule()) {
            relay.stop()
            dnsRelay = null
            return false
        }
        return try {
            agentDir.mkdirs()
            val builder = ProcessBuilder(
                binaryFile.absolutePath,
                "--secret-path", secretFile.absolutePath,
                "--socket-path", socketFile.absolutePath,
                "--log-path", agentLogFile.absolutePath
            )
            builder.directory(agentDir)
            builder.redirectErrorStream(true)
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(consoleFile))
            val started = builder.start()
            process = started
            log("Agente playit avviato con UID " + uid)
            thread(started, onUnexpectedExit)
            true
        } catch (e: Exception) {
            log("Impossibile avviare l'agente: " + e.message)
            removeRedirectRule()
            relay.stop()
            dnsRelay = null
            false
        }
    }

    fun stop() {
        val current = process
        process = null
        if (current != null) {
            try {
                current.destroy()
                if (!current.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    current.destroyForcibly()
                }
            } catch (e: Exception) {
                log("Errore durante l'arresto dell'agente: " + e.message)
            }
            killOrphanAgents()
        }
        dnsRelay?.stop()
        dnsRelay = null
        removeRedirectRule()
    }

    private fun thread(started: Process, onUnexpectedExit: () -> Unit) {
        Thread({
            try {
                started.waitFor()
            } catch (e: Exception) {
            }
            if (process === started) {
                process = null
                onUnexpectedExit()
            }
        }, "playit-agent-watch").apply { isDaemon = true; start() }
    }

    private fun clearStaleAgentFiles(): Boolean {
        val entries = agentDir.listFiles() ?: return true
        var ok = true
        for (entry in entries) {
            val name = entry.name
            if (name != "playit.toml" && name != "playitd.sock" && !name.startsWith("playitd.log")) {
                continue
            }
            if (entry.delete()) {
                continue
            }
            val removal = runRoot("rm -f " + entry.absolutePath)
            if (entry.exists()) {
                log("Impossibile eliminare " + entry.absolutePath + ": " + removal.second)
                ok = false
            }
        }
        return ok
    }

    private fun storedSecret(): String {
        return try {
            ApiKeyStore(context).getPlayitSecret() ?: ""
        } catch (e: Exception) {
            log("Lettura del segreto non riuscita: " + e.message)
            ""
        }
    }

    private fun secretProblem(secret: String): String? {
        if (secret.isBlank()) {
            return "Segreto playit mancante"
        }
        if (!secret.all { it in "0123456789abcdefABCDEF" }) {
            return "Il segreto playit deve essere esadecimale"
        }
        if (secret.length % 2 != 0) {
            return "Il segreto playit ha lunghezza dispari"
        }
        return null
    }

    private fun writeSecretFile(secret: String): Boolean {
        return try {
            agentDir.mkdirs()
            secretFile.writeText("secret_key = \"" + secret + "\"\n")
            true
        } catch (e: Exception) {
            log("Impossibile scrivere " + secretFile.absolutePath + ": " + e.message)
            false
        }
    }

    private fun resolveResolvers(): List<InetAddress> {
        val resolvers = linkedSetOf<InetAddress>()
        try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val linkProperties = manager.getLinkProperties(manager.activeNetwork)
            linkProperties?.dnsServers?.forEach { resolvers.add(it) }
        } catch (e: Exception) {
            log("Lettura dei DNS dalla rete attiva non riuscita: " + e.message)
        }
        for (index in 1..4) {
            try {
                val propertyProcess = ProcessBuilder("getprop", "net.dns" + index).redirectErrorStream(true).start()
                val value = propertyProcess.inputStream.bufferedReader().readText().trim()
                propertyProcess.waitFor(3, TimeUnit.SECONDS)
                if (value.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$"))) {
                    resolvers.add(InetAddress.getByName(value))
                }
            } catch (e: Exception) {
            }
        }
        if (resolvers.isEmpty()) {
            resolvers.add(InetAddress.getByName(DNS_FALLBACK_PRIMARY))
            resolvers.add(InetAddress.getByName(DNS_FALLBACK_SECONDARY))
            log("Nessun DNS di rete trovato: uso " + DNS_FALLBACK_PRIMARY + " e " + DNS_FALLBACK_SECONDARY)
        } else {
            log("DNS di rete: " + resolvers.joinToString { it.hostAddress ?: "?" })
        }
        return resolvers.toList()
    }

    private fun redirectRuleSpec(add: Boolean): String {
        val verb = if (add) "-A" else "-D"
        return "iptables -t nat " + verb + " OUTPUT -p udp -d 127.0.0.1 --dport 53 -m owner --uid-owner " + uid + " -j REDIRECT --to-ports " + REDIRECT_PORT
    }

    private fun installRedirectRule(): Boolean {
        while (runRoot(redirectRuleSpec(false)).first) {
        }
        val add = runRoot(redirectRuleSpec(true))
        if (!add.first) {
            log("Impossibile installare la regola DNS con i permessi di root: " + add.second)
            log("Senza root l'agente non risolverà i nomi: serve Magisk attivo almeno al primo avvio")
            return false
        }
        redirectRuleActive = true
        log("Regola DNS installata per UID " + uid + " verso la porta " + REDIRECT_PORT)
        return true
    }

    private fun removeRedirectRule() {
        if (!redirectRuleActive) {
            return
        }
        redirectRuleActive = false
        while (runRoot(redirectRuleSpec(false)).first) {
        }
    }

    private fun runRoot(command: String): Pair<Boolean, String> {
        return try {
            val rootProcess = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val finished = rootProcess.waitFor(ROOT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                rootProcess.destroyForcibly()
                false to ("timeout di " + ROOT_COMMAND_TIMEOUT_SECONDS + "s")
            } else {
                val output = rootProcess.inputStream.bufferedReader().readText().trim()
                (rootProcess.exitValue() == 0) to output
            }
        } catch (e: Exception) {
            false to (e.message ?: "errore sconosciuto")
        }
    }

    private fun killOrphanAgents() {
        val myPid = android.os.Process.myPid()
        val entries = File("/proc").listFiles() ?: return
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid == myPid) {
                continue
            }
            val command = try {
                File(entry, "cmdline").readText(Charsets.ISO_8859_1)
            } catch (e: Exception) {
                continue
            }
            val executable = command.split('\u0000').firstOrNull() ?: continue
            if (executable != binaryFile.absolutePath) {
                continue
            }
            try {
                android.os.Process.killProcess(pid)
                log("Processo agente residuo terminato: " + pid)
            } catch (e: Exception) {
                log("Impossibile terminare il processo residuo " + pid + ": " + e.message)
            }
        }
    }
}