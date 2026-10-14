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
        title = getString(R.string.network_title)

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
            Toast.makeText(this, R.string.network_stop_before_replace_toast, Toast.LENGTH_SHORT).show()
            return
        }
        claimActive = true
        buttonClaimSecret.isEnabled = false
        buttonSaveSecret.isEnabled = false
        val code = PlayitClaimApi().generateCode()
        val url = PlayitClaimApi().claimUrl(code)
        textStatus.text = getString(R.string.network_claim_status_open)
        appendLog(getString(R.string.network_claim_log_code, code))
        appendLog(getString(R.string.network_claim_log_page, url))
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            appendLog(getString(R.string.network_browser_error, e.message ?: getString(R.string.unknown_error)))
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
                claimLog(getString(R.string.network_claim_expired_log))
                runOnUiThread { textStatus.text = getString(R.string.network_claim_expired_status) }
                finishClaim()
                return
            }
            try {
                val state = api.claimSetup(code)
                if (state == "useraccepted") {
                    accepted = true
                } else if (state == "userrejected") {
                    claimLog(getString(R.string.network_claim_rejected))
                    runOnUiThread { textStatus.text = getString(R.string.network_claim_rejected) }
                    finishClaim()
                    return
                } else if (state == "waitingforuser") {
                    runOnUiThread { textStatus.text = getString(R.string.network_claim_waiting) }
                    SystemClock.sleep(500)
                } else {
                    runOnUiThread { textStatus.text = getString(R.string.network_claim_status_open) }
                    SystemClock.sleep(500)
                }
            } catch (e: Exception) {
                claimLog(getString(R.string.network_contact_error, e.message ?: getString(R.string.unknown_error)))
                SystemClock.sleep(2000)
            }
        }
        if (!accepted) {
            return
        }
        claimLog(getString(R.string.network_claim_approved_log))
        runOnUiThread { textStatus.text = getString(R.string.network_secret_retrieving) }
        val exchangeDeadline = System.currentTimeMillis() + 60000L
        while (claimActive) {
            if (System.currentTimeMillis() > exchangeDeadline) {
                claimLog(getString(R.string.network_secret_retrieval_expired_log))
                runOnUiThread { textStatus.text = getString(R.string.network_secret_retrieval_expired_status) }
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
                claimLog(getString(R.string.network_secret_retrieval_failed_log, e.message ?: getString(R.string.unknown_error)))
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
            claimLog(getString(R.string.network_secret_received_invalid_log, problem))
            finishClaim()
            return
        }
        runOnUiThread {
            keyStore.setPlayitSecret(secret)
            secretAlreadyStored = true
            secretInputUnlocked = false
            inputSecret.setText("")
            applySecretUiState()
            textStatus.text = getString(R.string.network_secret_saved_auto)
            Toast.makeText(this, R.string.network_secret_saved_auto, Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, R.string.network_secret_empty_toast, Toast.LENGTH_SHORT).show()
            return
        }

        val problem = secretProblem(secret)
        if (problem != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.network_secret_invalid_title)
                .setMessage(problem)
                .setPositiveButton(R.string.ui_understood, null)
                .show()
            return
        }

        if (TunnelService.isAgentRunning) {
            Toast.makeText(this, R.string.network_stop_before_change_toast, Toast.LENGTH_SHORT).show()
            return
        }

        keyStore.setPlayitSecret(secret)
        secretAlreadyStored = true
        secretInputUnlocked = false
        inputSecret.setText("")
        applySecretUiState()
        Toast.makeText(this, R.string.network_secret_saved_local, Toast.LENGTH_SHORT).show()
        loadTunnels()
    }

    private fun secretProblem(value: String): String? {
        if (!value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return getString(R.string.network_secret_invalid_characters)
        }

        if (value.length % 2 != 0) {
            return getString(R.string.network_secret_odd_length, value.length)
        }

        if (value.length <= CLAIM_CODE_MAX_LENGTH) {
            return getString(R.string.network_secret_claim_code)
        }

        if (value.length < SECRET_MIN_LENGTH) {
            return getString(R.string.network_secret_too_short, value.length)
        }

        return null
    }

    private fun applySecretUiState() {
        if (secretAlreadyStored && !secretInputUnlocked) {
            inputSecret.isEnabled = false
            inputSecret.setText("")
            inputSecret.hint = getString(R.string.network_secret_saved_hint)
            buttonSaveSecret.text = getString(R.string.network_replace_button)
        } else {
            inputSecret.isEnabled = true
            inputSecret.hint = getString(
                if (secretAlreadyStored) R.string.network_secret_new_hint else R.string.network_secret_hint
            )
            buttonSaveSecret.text = getString(
                if (secretAlreadyStored) R.string.network_save_new_button else R.string.network_save_button
            )
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
                .setTitle(R.string.network_root_required_title)
                .setMessage(R.string.network_root_required_message)
                .setPositiveButton(R.string.ui_understood, null)
                .show()
            return
        }

        val secret = keyStore.getPlayitSecret()
        if (secret.isNullOrBlank()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.network_missing_secret_title)
                .setMessage(R.string.network_missing_secret_message)
                .setPositiveButton(R.string.ui_understood, null)
                .show()
            return
        }

        val problem = secretProblem(secret.trim())
        if (problem != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.network_invalid_stored_secret_title)
                .setMessage(problem)
                .setPositiveButton(R.string.ui_understood, null)
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
            textStatus.text = getString(R.string.network_no_secret_saved)
            containerTunnels.removeAllViews()
            return
        }

        buttonRefresh.isEnabled = false
        textStatus.text = getString(R.string.network_tunnels_loading)

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
                        textStatus.text = getString(R.string.network_tunnels_unavailable)
                        appendLog(
                            getString(
                                R.string.network_tunnel_read_error_log,
                                it.message ?: getString(R.string.unknown_error)
                            )
                        )
                        containerTunnels.removeAllViews()
                    }
                )
            }
        }.start()
    }

    private fun renderTunnels(tunnels: List<PlayitTunnel>) {
        containerTunnels.removeAllViews()

        if (tunnels.isEmpty()) {
            textStatus.text = getString(R.string.network_no_tunnels_configured)
            appendLog(getString(R.string.network_no_tunnels_log))
            return
        }

        textStatus.text = getString(R.string.network_tunnels_count, tunnels.size)

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
            text = tunnel.displayAddress.ifBlank { getString(R.string.network_unassigned_address) }
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
                text = getString(R.string.network_tunnel_disabled, tunnel.disabledReason)
                textSize = 12f
            }
            row.addView(warning)
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val copyButton = Button(this).apply {
            text = getString(R.string.network_copy_address)
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
            Toast.makeText(this, R.string.network_no_tunnel_address, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.network_clipboard_label), address))
        Toast.makeText(this, R.string.network_address_copied, Toast.LENGTH_SHORT).show()
    }

    private fun applyAgentState(running: Boolean) {
        buttonStartStop.text = getString(
            if (running) R.string.network_stop_agent_button else R.string.network_start_agent_button
        )
        buttonStartStop.isEnabled = true
    }

    private fun refreshAgentUi() {
        val running = TunnelService.isAgentRunning
        applyAgentState(running)
        appendLog(getString(if (running) R.string.network_agent_running_log else R.string.network_agent_stopped_log))
    }

    private fun appendLog(message: String) {
        textLog.append("$message\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onBackPressed() {
        if (TunnelService.isAgentRunning) {
            AlertDialog.Builder(this)
                .setTitle(R.string.network_agent_active_title)
                .setMessage(R.string.network_agent_active_message)
                .setPositiveButton(R.string.network_exit_button) { _, _ -> super.onBackPressed() }
                .setNegativeButton(R.string.network_stay_button, null)
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