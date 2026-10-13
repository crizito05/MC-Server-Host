package com.pcaudio.minecraft_server.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.pcaudio.minecraft_server.R
import com.pcaudio.minecraft_server.core.ApiKeyStore
import com.pcaudio.minecraft_server.core.PlayitAgentManager
import com.pcaudio.minecraft_server.core.PlayitApi
import com.pcaudio.minecraft_server.core.PlayitClaimApi
import com.pcaudio.minecraft_server.core.PlayitTunnel
import com.pcaudio.minecraft_server.core.RootChecker
import com.pcaudio.minecraft_server.service.TunnelService

class NetworkActivity : AppCompatActivity() {

    private lateinit var inputSecret: EditText
    private lateinit var buttonSaveSecret: Button
    private lateinit var buttonClaimSecret: Button
    private lateinit var buttonStartStop: Button
    private lateinit var buttonRefresh: Button
    private lateinit var textStatus: TextView
    private lateinit var containerTunnels: LinearLayout
    private lateinit var textLog: TextView
    private lateinit var logScroll: ScrollView

    private lateinit var keyStore: ApiKeyStore
    private lateinit var agentManager: PlayitAgentManager

    private var secretAlreadyStored = false
    private var secretInputUnlocked = false

    @Volatile
    private var claimActive = false

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread { appendLog(line) }
    }

    private val stateListener: (Boolean) -> Unit = { running ->
        runOnUiThread { applyAgentState(running) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network)
        title = "Connessione dall'esterno"

        keyStore = ApiKeyStore(this)
        agentManager = PlayitAgentManager(this)

        inputSecret = findViewById(R.id.inputSecret)
        buttonSaveSecret = findViewById(R.id.buttonSaveSecret)
        buttonClaimSecret = findViewById(R.id.buttonClaimSecret)
        buttonStartStop = findViewById(R.id.buttonStartStopAgent)
        buttonRefresh = findViewById(R.id.buttonRefreshTunnels)
        textStatus = findViewById(R.id.textNetworkStatus)
        containerTunnels = findViewById(R.id.containerTunnels)
        textLog = findViewById(R.id.textNetworkLog)
        logScroll = findViewById(R.id.networkLogScroll)

        logScroll.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        buttonSaveSecret.setOnClickListener { onSaveSecretClicked() }
        buttonClaimSecret.setOnClickListener { onClaimClicked() }
        buttonStartStop.setOnClickListener { onStartStopClicked() }
        buttonRefresh.setOnClickListener { loadTunnels() }
        findViewById<FloatingActionButton>(R.id.fabHelp).setOnClickListener {
            startActivity(Intent(this, NetworkHelpActivity::class.java))
        }

        secretAlreadyStored = keyStore.hasPlayitSecret()
        secretInputUnlocked = !secretAlreadyStored
        applySecretUiState()
        refreshAgentUi()
    }

    override fun onStart() {
        super.onStart()
        TunnelService.addLogListener(logListener)
        TunnelService.addStateListener(stateListener)
        refreshAgentUi()
        if (keyStore.hasPlayitSecret()) loadTunnels()
    }

    override fun onStop() {
        TunnelService.removeLogListener(logListener)
        TunnelService.removeStateListener(stateListener)
        super.onStop()
    }

    override fun onDestroy() {
        claimActive = false
        super.onDestroy()
    }

    private fun onClaimClicked() {
        if (claimActive) {
            return
        }
        if (TunnelService.isAgentRunning) {
            Toast.makeText(this, "Ferma l'agente prima di sostituire il segreto", Toast.LENGTH_SHORT).show()
            return
        }
        claimActive = true
        buttonClaimSecret.isEnabled = false
        buttonSaveSecret.isEnabled = false
        val code = PlayitClaimApi().generateCode()
        val url = PlayitClaimApi().claimUrl(code)
        textStatus.text = "Attivazione in corso: apri la pagina nel browser"
        appendLog("Codice di attivazione: " + code)
        appendLog("Pagina da approvare: " + url)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            appendLog("Impossibile aprire il browser: " + e.message)
            finishClaim()
            return
        }
        Thread { runClaim(code) }.start()
    }

    private fun runClaim(code: String) {
        val api = PlayitClaimApi()
        val setupDeadline = System.currentTimeMillis() + 600000L
        var accepted = false
        while (claimActive && !accepted) {
            if (System.currentTimeMillis() > setupDeadline) {
                claimLog("Attivazione scaduta: premi di nuovo il pulsante per riprovare")
                runOnUiThread { textStatus.text = "Attivazione scaduta" }
                finishClaim()
                return
            }
            try {
                val state = api.claimSetup(code)
                if (state == "useraccepted") {
                    accepted = true
                } else if (state == "userrejected") {
                    claimLog("Attivazione rifiutata nel browser")
                    runOnUiThread { textStatus.text = "Attivazione rifiutata nel browser" }
                    finishClaim()
                    return
                } else if (state == "waitingforuser") {
                    runOnUiThread { textStatus.text = "Attivazione in corso: approva nel browser" }
                    SystemClock.sleep(500)
                } else {
                    runOnUiThread { textStatus.text = "Attivazione in corso: apri la pagina nel browser" }
                    SystemClock.sleep(500)
                }
            } catch (e: Exception) {
                claimLog("Contatto con playit.gg temporaneamente non riuscito: " + e.message)
                SystemClock.sleep(2000)
            }
        }
        if (!accepted) {
            return
        }
        claimLog("Programma approvato: recupero del segreto in corso")
        runOnUiThread { textStatus.text = "Recupero del segreto in corso" }
        val exchangeDeadline = System.currentTimeMillis() + 60000L
        while (claimActive) {
            if (System.currentTimeMillis() > exchangeDeadline) {
                claimLog("Recupero del segreto scaduto: premi di nuovo il pulsante per riprovare")
                runOnUiThread { textStatus.text = "Recupero del segreto scaduto" }
                finishClaim()
                return
            }
            try {
                val secret = api.claimExchange(code)
                if (secret != null) {
                    applyClaimedSecret(secret)
                    return
                }
            } catch (e: Exception) {
                claimLog("Recupero del segreto non riuscito: " + e.message)
                finishClaim()
                return
            }
            SystemClock.sleep(500)
        }
        finishClaim()
    }

    private fun applyClaimedSecret(secret: String) {
        val problem = secretProblem(secret)
        if (problem != null) {
            claimLog("Il segreto ricevuto non è valido: " + problem)
            finishClaim()
            return
        }
        runOnUiThread {
            keyStore.setPlayitSecret(secret)
            secretAlreadyStored = true
            secretInputUnlocked = false
            inputSecret.setText("")
            applySecretUiState()
            textStatus.text = "Segreto salvato automaticamente"
            Toast.makeText(this, "Segreto salvato automaticamente", Toast.LENGTH_SHORT).show()
            finishClaim()
            loadTunnels()
        }
    }

    private fun finishClaim() {
        claimActive = false
        runOnUiThread {
            buttonClaimSecret.isEnabled = true
            buttonSaveSecret.isEnabled = true
        }
    }

    private fun claimLog(line: String) {
        runOnUiThread { appendLog(line) }
    }

    private fun onSaveSecretClicked() {
        if (secretAlreadyStored && !secretInputUnlocked) {
            secretInputUnlocked = true
            inputSecret.setText("")
            applySecretUiState()
            inputSecret.requestFocus()
            return
        }

        val secret = inputSecret.text.toString().trim()

        if (secret.isBlank()) {
            Toast.makeText(this, "Incolla il segreto dell'agente playit.gg", Toast.LENGTH_SHORT).show()
            return
        }

        val problem = secretProblem(secret)
        if (problem != null) {
            AlertDialog.Builder(this)
                .setTitle("Questo non è il segreto dell'agente")
                .setMessage(problem)
                .setPositiveButton("Ho capito", null)
                .show()
            return
        }

        if (TunnelService.isAgentRunning) {
            Toast.makeText(this, "Ferma l'agente prima di cambiare il segreto", Toast.LENGTH_SHORT).show()
            return
        }

        keyStore.setPlayitSecret(secret)
        secretAlreadyStored = true
        secretInputUnlocked = false
        inputSecret.setText("")
        applySecretUiState()
        Toast.makeText(this, "Segreto salvato sul dispositivo", Toast.LENGTH_SHORT).show()
        loadTunnels()
    }

    private fun secretProblem(value: String): String? {
        if (!value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return "Il segreto di un agente playit.gg è una stringa di soli caratteri esadecimali, cioè cifre da 0 a 9 e lettere da a a f, senza trattini, spazi o prefissi. Quello che hai incollato contiene altri caratteri."
        }

        if (value.length % 2 != 0) {
            return "Il segreto ha una lunghezza dispari, ${value.length} caratteri: una stringa esadecimale valida ha lunghezza pari. Probabilmente ne hai copiato solo un pezzo."
        }

        if (value.length <= CLAIM_CODE_MAX_LENGTH) {
            return "Questa è la forma di un codice di attivazione, non di un segreto. Il codice compare nell'indirizzo playit.gg/claim/ e serve solo ad approvare l'agente nel browser: il segreto vero te lo restituisce il programma sul computer dopo l'approvazione, ed è una stringa esadecimale molto più lunga."
        }

        if (value.length < SECRET_MIN_LENGTH) {
            return "Il segreto ha solo ${value.length} caratteri, troppo pochi per essere il segreto di un agente. Copia per intero la stringa restituita al termine della procedura di attivazione."
        }

        return null
    }

    private fun applySecretUiState() {
        if (secretAlreadyStored && !secretInputUnlocked) {
            inputSecret.isEnabled = false
            inputSecret.setText("")
            inputSecret.hint = "Segreto salvato sul dispositivo"
            buttonSaveSecret.text = "Sostituisci segreto"
        } else {
            inputSecret.isEnabled = true
            inputSecret.hint = if (secretAlreadyStored) "Incolla il nuovo segreto dell'agente" else "Incolla il segreto dell'agente playit.gg"
            buttonSaveSecret.text = if (secretAlreadyStored) "Salva il nuovo segreto" else "Salva segreto"
        }
    }

    private fun onStartStopClicked() {
        if (TunnelService.isAgentRunning) {
            val intent = Intent(this, TunnelService::class.java)
            intent.action = TunnelService.ACTION_STOP
            startService(intent)
            buttonStartStop.isEnabled = false
            return
        }

        if (!RootChecker().isDeviceRooted()) {
            AlertDialog.Builder(this)
                .setTitle("Serve il root")
                .setMessage("L'agente deve essere eseguito da una partizione con permesso di esecuzione, e su Android questo richiede il root. Senza root il tunnel non può partire.")
                .setPositiveButton("Ho capito", null)
                .show()
            return
        }

        val secret = keyStore.getPlayitSecret()
        if (secret.isNullOrBlank()) {
            AlertDialog.Builder(this)
                .setTitle("Segreto mancante")
                .setMessage("Incolla prima il segreto del tuo agente playit.gg, poi avvia l'agente.")
                .setPositiveButton("Ho capito", null)
                .show()
            return
        }

        val problem = secretProblem(secret.trim())
        if (problem != null) {
            AlertDialog.Builder(this)
                .setTitle("Il segreto salvato non è valido")
                .setMessage(problem)
                .setPositiveButton("Ho capito", null)
                .show()
            return
        }

        textLog.text = ""
        buttonStartStop.isEnabled = false
        val intent = Intent(this, TunnelService::class.java)
        intent.putExtra("secret", secret)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun loadTunnels() {
        val secret = keyStore.getPlayitSecret()
        if (secret.isNullOrBlank()) {
            textStatus.text = "Nessun segreto salvato"
            containerTunnels.removeAllViews()
            return
        }

        buttonRefresh.isEnabled = false
        textStatus.text = "Lettura tunnel in corso..."

        Thread {
            val outcome = try {
                Result.success(PlayitApi().listTunnels(secret))
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                buttonRefresh.isEnabled = true
                outcome.fold(
                    onSuccess = { renderTunnels(it) },
                    onFailure = {
                        textStatus.text = "Impossibile leggere i tunnel"
                        appendLog("Errore lettura tunnel: ${it.message}")
                        containerTunnels.removeAllViews()
                    }
                )
            }
        }.start()
    }

    private fun renderTunnels(tunnels: List<PlayitTunnel>) {
        containerTunnels.removeAllViews()

        if (tunnels.isEmpty()) {
            textStatus.text = "Nessun tunnel configurato sul tuo account playit.gg"
            appendLog("Nessun tunnel: creane uno su playit.gg con tipo Minecraft Java e porta locale 25565")
            return
        }

        textStatus.text = "${tunnels.size} tunnel configurati"

        for (tunnel in tunnels) {
            containerTunnels.addView(buildTunnelRow(tunnel))
        }
    }

    private fun buildTunnelRow(tunnel: PlayitTunnel): View {
        val padding = (12 * resources.displayMetrics.density).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, padding, 0, padding)
        }

        val address = TextView(this).apply {
            text = tunnel.displayAddress.ifBlank { "indirizzo non assegnato" }
            textSize = 16f
            setTextIsSelectable(true)
        }
        row.addView(address)

        val details = TextView(this).apply {
            text = listOf(tunnel.name, tunnel.tunnelType, tunnel.portType)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            textSize = 12f
        }
        row.addView(details)

        if (tunnel.disabledReason != null) {
            val warning = TextView(this).apply {
                text = "Disattivato: ${tunnel.disabledReason}"
                textSize = 12f
            }
            row.addView(warning)
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val copyButton = Button(this).apply {
            text = "Copia indirizzo"
            setOnClickListener { copyToClipboard(tunnel.displayAddress) }
        }
        actions.addView(copyButton)

        row.addView(actions)

        val separator = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * resources.displayMetrics.density).toInt()
            ).apply { topMargin = padding }
            setBackgroundColor(0x22000000)
        }
        row.addView(separator)

        return row
    }

    private fun copyToClipboard(address: String) {
        if (address.isBlank()) {
            Toast.makeText(this, "Questo tunnel non ha ancora un indirizzo", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("indirizzo_server", address))
        Toast.makeText(this, "Indirizzo copiato", Toast.LENGTH_SHORT).show()
    }

    private fun applyAgentState(running: Boolean) {
        buttonStartStop.text = if (running) "Ferma agente" else "Avvia agente"
        buttonStartStop.isEnabled = true
    }

    private fun refreshAgentUi() {
        val running = TunnelService.isAgentRunning
        applyAgentState(running)
        appendLog(if (running) "Agente in esecuzione" else "Agente fermo")
    }

    private fun appendLog(message: String) {
        textLog.append("$message\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onBackPressed() {
        if (TunnelService.isAgentRunning) {
            AlertDialog.Builder(this)
                .setTitle("Agente ancora attivo")
                .setMessage("Il tunnel resta attivo anche se esci da questa schermata, perché gira in un servizio in primo piano. Per fermarlo usa il pulsante Ferma agente o la notifica.")
                .setPositiveButton("Esci") { _, _ -> super.onBackPressed() }
                .setNegativeButton("Resta qui", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val CLAIM_CODE_MAX_LENGTH = 16
        private const val SECRET_MIN_LENGTH = 32
    }
}