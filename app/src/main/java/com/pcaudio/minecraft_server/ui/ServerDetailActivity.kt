package com.pcaudio.minecraft_server.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pcaudio.minecraft_server.R
import com.pcaudio.minecraft_server.core.ApiKeyStore
import com.pcaudio.minecraft_server.core.PlayitApi
import com.pcaudio.minecraft_server.core.PlayitTunnel
import com.pcaudio.minecraft_server.core.ServerConfig
import com.pcaudio.minecraft_server.core.ServerRepository
import com.pcaudio.minecraft_server.service.MinecraftServerService

class ServerDetailActivity : AppCompatActivity() {

    private lateinit var server: ServerConfig
    private lateinit var textConsole: TextView
    private lateinit var consoleScroll: ScrollView
    private lateinit var buttonStartStop: Button
    private lateinit var inputCommand: EditText
    private lateinit var textTunnelAddress: TextView
    private var tunnelAddress = ""
    private var tunnelAddressProblem = ""

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread { appendConsoleLine(line) }
    }

    private val stateListener: (Boolean) -> Unit = { running ->
        runOnUiThread { updateStartStopButton(running) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_detail)

        val serverId = intent.getStringExtra("server_id")
        val repository = ServerRepository(this)
        val found = repository.listServers().firstOrNull { it.id == serverId }
        if (found == null) {
            finish()
            return
        }
        server = found

        findViewById<TextView>(R.id.textDetailName).text = server.name
        val loaderLabel = if (server.loaderVersion != null) "${server.loader.name} ${server.loaderVersion}" else server.loader.name
        findViewById<TextView>(R.id.textDetailInfo).text = "${server.minecraftVersion} · $loaderLabel · ${server.ramMb}MB RAM"

        textConsole = findViewById(R.id.textConsole)
        consoleScroll = findViewById(R.id.consoleScroll)
        buttonStartStop = findViewById(R.id.buttonStartStop)
        inputCommand = findViewById(R.id.inputCommand)
        textTunnelAddress = findViewById(R.id.textTunnelAddress)

        textTunnelAddress.setOnClickListener {
            if (tunnelAddress.isNotBlank()) {
                showCopiableDialog("Indirizzo del server", tunnelAddress, "Indirizzo copiato")
            } else if (tunnelAddressProblem.isNotBlank()) {
                showCopiableDialog("Indirizzo non disponibile", tunnelAddressProblem, "Testo copiato")
            }
        }

        consoleScroll.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        textConsole.text = MinecraftServerService.getLogBuffer().joinToString("\n")
        updateStartStopButton(MinecraftServerService.isRunning(server.id))

        buttonStartStop.setOnClickListener { onStartStopClicked() }
        findViewById<Button>(R.id.buttonSendCommand).setOnClickListener { onSendCommand() }

        findViewById<Button>(R.id.buttonConfigureProperties).setOnClickListener {
            val intent = Intent(this, ServerPropertiesActivity::class.java)
            intent.putExtra("server_id", server.id)
            startActivity(intent)
        }

        findViewById<Button>(R.id.buttonViewLogs).setOnClickListener {
            val intent = Intent(this, ServerLogsActivity::class.java)
            intent.putExtra("server_id", server.id)
            startActivity(intent)
        }

        val browseModsButton = findViewById<Button>(R.id.buttonBrowseMods)
        val viewInstalledButton = findViewById<Button>(R.id.buttonViewInstalledMods)

        if (server.loader == com.pcaudio.minecraft_server.core.ModLoader.VANILLA) {
            browseModsButton.visibility = View.GONE
            viewInstalledButton.visibility = View.GONE
        } else {
            browseModsButton.setOnClickListener {
                val intent = Intent(this, ModBrowserActivity::class.java)
                intent.putExtra("server_id", server.id)
                startActivity(intent)
            }
            viewInstalledButton.setOnClickListener {
                val intent = Intent(this, InstalledModsActivity::class.java)
                intent.putExtra("server_id", server.id)
                startActivity(intent)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        MinecraftServerService.addLogListener(logListener)
        MinecraftServerService.addStateListener(stateListener)
        refreshTunnelAddress()
    }

    override fun onStop() {
        super.onStop()
        MinecraftServerService.removeLogListener(logListener)
        MinecraftServerService.removeStateListener(stateListener)
    }

    override fun onBackPressed() {
        if (MinecraftServerService.isRunning(server.id)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Server ancora acceso")
                .setMessage("Il server è ancora in esecuzione. Uscendo ora, il processo continuerà a girare in background finché non lo fermi. Vuoi uscire comunque?")
                .setPositiveButton("Esci comunque") { _, _ -> super.onBackPressed() }
                .setNegativeButton("Resta qui", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    private fun refreshTunnelAddress() {
        Thread {
            var address = ""
            var problem = ""
            try {
                val secret = ApiKeyStore(this).getPlayitSecret()
                if (!secret.isNullOrBlank()) {
                    val tunnels: List<PlayitTunnel> = PlayitApi().listTunnels(secret)
                    val usable = tunnels.map { it.displayAddress }.filter { it.isNotBlank() }
                    val first = usable.firstOrNull()
                    if (first == null) {
                        problem = "Nessun tunnel playit con indirizzo assegnato: controlla Connessione dall'esterno"
                    } else {
                        address = first
                    }
                }
            } catch (e: Exception) {
                problem = e.message ?: "errore sconosciuto"
            }
            val finalAddress = address
            val finalProblem = problem
            runOnUiThread {
                tunnelAddress = finalAddress
                tunnelAddressProblem = finalProblem
                if (finalAddress.isNotBlank()) {
                    textTunnelAddress.text = finalAddress
                    textTunnelAddress.visibility = View.VISIBLE
                } else if (finalProblem.isNotBlank()) {
                    textTunnelAddress.text = "indirizzo non disponibile"
                    textTunnelAddress.visibility = View.VISIBLE
                } else {
                    textTunnelAddress.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun showCopiableDialog(title: String, body: String, toastText: String) {
        val box = TextView(this)
        box.text = body
        box.setTextIsSelectable(true)
        box.textSize = 15f
        val padding = (16 * resources.displayMetrics.density).toInt()
        box.setPadding(padding, padding, padding, padding)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Copia") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("server_info", body))
                Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun onStartStopClicked() {
        if (MinecraftServerService.isRunning(server.id)) {
            val intent = Intent(this, MinecraftServerService::class.java)
            intent.action = MinecraftServerService.ACTION_STOP
            startService(intent)
        } else {
            textConsole.text = ""
            val intent = Intent(this, MinecraftServerService::class.java)
            intent.putExtra("server_dir", server.directoryPath)
            intent.putExtra("server_id", server.id)
            intent.putExtra("ram_mb", server.ramMb)
            startServiceCompat(intent)
        }
    }

    private fun onSendCommand() {
        val command = inputCommand.text.toString().trim()
        if (command.isBlank()) return
        val intent = Intent(this, MinecraftServerService::class.java)
        intent.action = MinecraftServerService.ACTION_COMMAND
        intent.putExtra(MinecraftServerService.EXTRA_COMMAND, command)
        startService(intent)
        inputCommand.setText("")
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun appendConsoleLine(line: String) {
        textConsole.append("$line\n")
        consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateStartStopButton(running: Boolean) {
        buttonStartStop.text = if (running) "Ferma" else "Avvia"
    }
}