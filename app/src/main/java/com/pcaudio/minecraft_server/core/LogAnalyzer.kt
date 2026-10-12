package com.pcaudio.minecraft_server.core

data class LogIssue(val severity: String, val description: String, val rawLine: String)

class LogAnalyzer {

    fun analyze(logContent: String): List<LogIssue> {
        val issues = mutableListOf<LogIssue>()
        val lines = logContent.lines()

        for (line in lines) {
            when {
                line.contains("MixinApplyError") || line.contains("mixin apply failed") -> {
                    issues.add(LogIssue("CONFLITTO MOD", "Una mod ha modificato codice in conflitto con un'altra mod (errore Mixin)", line))
                }
                line.contains("DuplicateModsFoundException") || line.contains("duplicate mod") -> {
                    issues.add(LogIssue("MOD DUPLICATA", "Due file mod dichiarano lo stesso ID, rimuovine uno", line))
                }
                line.contains("MissingDependencyException") || (line.contains("requires") && line.contains("missing")) -> {
                    issues.add(LogIssue("DIPENDENZA MANCANTE", "Una mod richiede un'altra mod o libreria non installata", line))
                }
                line.contains("IncompatibleModException") || line.contains("incompatible") && line.contains("mod") -> {
                    issues.add(LogIssue("INCOMPATIBILITÀ", "Due mod sono dichiarate incompatibili tra loro", line))
                }
                line.contains("UnsupportedClassVersionError") -> {
                    issues.add(LogIssue("VERSIONE JAVA", "Una mod richiede una versione Java diversa da quella in uso", line))
                }
                line.contains("session.lock") && line.contains("already locked") -> {
                    issues.add(LogIssue("MONDO BLOCCATO", "Un'altra istanza del server sta già usando questo mondo", line))
                }
                line.contains("OutOfMemoryError") -> {
                    issues.add(LogIssue("MEMORIA INSUFFICIENTE", "Il server ha esaurito la RAM assegnata, prova ad aumentarla", line))
                }
                line.contains("CrashReport") || line.contains("Exception in server tick loop") -> {
                    issues.add(LogIssue("CRASH SERVER", "Il server si è arrestato in modo anomalo durante l'esecuzione", line))
                }
                line.contains("Failed to initialize server") -> {
                    issues.add(LogIssue("AVVIO FALLITO", "Il server non è riuscito a inizializzarsi, controlla i data pack e le mod per incompatibilità", line))
                }
                line.contains("(incompatible)") -> {
                    val packName = line.substringBefore("(incompatible)").trim().substringAfterLast(",").trim()
                    issues.add(LogIssue("DATA PACK INCOMPATIBILE", "Un data pack (probabilmente di una mod: $packName) non è compatibile con questa versione di Minecraft", line))
                }
                line.contains("Description: Exception in server tick loop") -> {
                    issues.add(LogIssue("CRASH ALL'AVVIO", "Il server si è arrestato durante l'inizializzazione, controlla il crash report completo per i dettagli", line))
                }
                line.contains("Failed to initialize server") -> {
                    issues.add(LogIssue("AVVIO FALLITO", "Il server non è riuscito a inizializzarsi, probabile data pack incompatibile da una mod", line))
                }
                line.contains("(incompatible)") -> {
                    issues.add(LogIssue("DATA PACK INCOMPATIBILE", "Una mod include un data pack che il server considera incompatibile con questa versione Minecraft. Prova ad aggiornare la mod o avviare con --safeMode", line))
                }
                Regex("requires\\s+\\{([^}]+)\\}", RegexOption.IGNORE_CASE).containsMatchIn(line) -> {
                    val match = Regex("requires\\s+\\{([^}]+)\\}", RegexOption.IGNORE_CASE).find(line)
                    val dependency = match?.groupValues?.get(1) ?: "sconosciuta"
                    issues.add(LogIssue("DIPENDENZA MANCANTE", "Manca una mod richiesta: $dependency. Installala dal catalogo per risolvere.", line))
                }
                line.contains("Mod resolution failed") -> {
                    issues.add(LogIssue("DIPENDENZE NON RISOLTE", "Una o più mod richiedono altre mod non installate, controlla il messaggio completo qui sotto per i dettagli", line))
                }
                line.contains("witnesses:") && line.contains("depends on") -> {
                    issues.add(LogIssue("DIPENDENZA MANCANTE", "Una mod dipende da un'altra mod non presente", line))
                }
                line.contains("libm.so.6") || (line.contains("dlopen failed") && line.contains(".so")) -> {
                    issues.add(LogIssue("LIBRERIA NATIVA MANCANTE", "Una mod include un binario nativo (es. Sable/Rapier) non compatibile con questo ambiente Android. Rimuovi la mod o cerca una versione alternativa", line))
                }
            }
        }

        return issues
    }

    fun summarize(logContent: String): String {
        val issues = analyze(logContent)
        if (issues.isEmpty()) {
            return if (logContent.contains("Done") && logContent.contains("For help")) "Avviato senza problemi" else "Nessun problema rilevato"
        }
        val distinctSeverities = issues.map { it.severity }.distinct()
        return "${issues.size} problemi: ${distinctSeverities.joinToString(", ")}"
    }
}