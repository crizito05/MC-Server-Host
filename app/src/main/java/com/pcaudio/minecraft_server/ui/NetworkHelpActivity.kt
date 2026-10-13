package com.pcaudio.minecraft_server.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pcaudio.minecraft_server.R

class NetworkHelpActivity : AppCompatActivity() {

    private val sections = listOf(
        Pair("Come funziona", "Per giocare da fuori casa serve un tunnel: playit.gg riceve le connessioni dei giocatori e le passa al server sul telefono. Serve un agente sul telefono, identificato da un segreto, e si ottiene in due modi."),
        Pair("Versione dal telefono, senza computer", "1) In Connessione dall'esterno premi \"Attiva agente dal telefono\".\n2) Si apre il browser su una pagina playit.gg/claim/… : accedi al tuo account (o creane uno) e premi il pulsante per approvare il programma.\n3) Torna nell'app senza chiuderla: il segreto arriva e viene salvato da solo, senza copiare niente. La conferma è la scritta Segreto salvato automaticamente."),
        Pair("Versione dal computer", "1) Sul computer installa playit-cli ed esegui playit-cli setup: il programma mostra un link playit.gg/claim/… da approvare nel browser.\n2) Il segreto si trova nel file C:\\ProgramData\\playit_gg\\playit.toml, alla riga secret_key = \"…\" : copia solo la stringa tra le virgolette, è lunga e fatta di soli numeri e lettere da a a f. Su Linux il file è /etc/playit/playit.toml e il comando playit-cli secret-path stampa la posizione esatta.\n3) Incolla la stringa nel campo \"Incolla il segreto di playit.gg\" e premi Salva segreto."),
        Pair("Il segreto non è il codice di attivazione", "Il codice breve di 10 caratteri che compare nell'indirizzo playit.gg/claim/ serve solo ad approvare l'agente nel browser e non è il segreto. Se lo incolli nel campo del segreto, l'app lo riconosce e ti avvisa."),
        Pair("Poi, per entrambe le versioni", "1) Premi Avvia agente: la notifica deve mostrare Tunnel attivo e nel log deve comparire Agente avviato.\n2) Crea il tunnel dal pannello playit.gg: tipo Minecraft Java, porta locale 25565 o la porta del tuo server.\n3) Condividi l'indirizzo che compare accanto al nome del server: gli amici lo inseriscono in Minecraft come un normale server multiplayer.")
    )

    private val troubles = listOf(
        "Se nel log compare Could not reach the playit API: controlla la connessione di rete, poi Ferma agente e Avvia agente.",
        "Se dopo Leggi indirizzi non compare nessun indirizzo: il tunnel non è ancora assegnato o è disattivato, controlla sul sito playit.gg.",
        "Se l'attivazione dal telefono non completa o il codice scade: premi di nuovo il pulsante, ogni tentativo genera un codice nuovo.",
        "L'attivazione dal telefono crea un agente nuovo sul tuo account: se avevi già un agente funzionante con un tunnel assegnato, da playit.gg controlla quale agente usa il tunnel.",
        "Il tunnel funziona solo mentre l'agente è attivo: se riavvii il telefono o chiudi l'agente, riavvialo prima di giocare."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_help)
        title = "Guida alla connessione dall'esterno"

        val container = findViewById<LinearLayout>(R.id.containerHelpSteps)
        for (section in sections) {
            container.addView(buildHeading(section.first))
            container.addView(buildBody(section.second))
        }
        container.addView(buildHeading("Se qualcosa non va"))
        for (trouble in troubles) {
            container.addView(buildBody(trouble))
        }

        findViewById<Button>(R.id.buttonCloseHelp).setOnClickListener { finish() }
    }

    private fun buildHeading(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }
    }

    private fun buildBody(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        }
    }
}