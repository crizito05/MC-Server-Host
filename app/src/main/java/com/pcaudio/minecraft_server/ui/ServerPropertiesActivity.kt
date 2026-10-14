package com.pcaudio.minecraft_server.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.pcaudio.minecraft_server.R
import com.pcaudio.minecraft_server.core.ServerPropertiesManager
import com.pcaudio.minecraft_server.core.ServerRepository
import com.pcaudio.minecraft_server.service.MinecraftServerService
import java.io.File

class ServerPropertiesActivity : AppCompatActivity() {

    private lateinit var propertiesFile: File
    private lateinit var serverIdForCheck: String
    private val manager = ServerPropertiesManager()
    private var isSaving = false

    private lateinit var switchWhitelist: Switch
    private lateinit var switchPvp: Switch
    private lateinit var switchOnlineMode: Switch
    private lateinit var spinnerDifficulty: Spinner
    private lateinit var spinnerGamemode: Spinner
    private lateinit var inputMaxPlayers: EditText
    private lateinit var inputViewDistance: EditText
    private lateinit var inputMotd: EditText
    private lateinit var inputRawProperties: EditText

    private val difficultyOptions = listOf("peaceful", "easy", "normal", "hard")
    private val gamemodeOptions = listOf("survival", "creative", "adventure", "spectator")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_properties)

        val serverId = intent.getStringExtra("server_id")
        serverIdForCheck = serverId ?: ""

        val repository = ServerRepository(this)
        val config = repository.listServers().firstOrNull { it.id == serverId }
        if (config == null) {
            finish()
            return
        }
        propertiesFile = File(config.directoryPath, "server.properties")
        title = getString(R.string.props_title, config.name)

        switchWhitelist = findViewById(R.id.switchWhitelist)
        switchPvp = findViewById(R.id.switchPvp)
        switchOnlineMode = findViewById(R.id.switchOnlineMode)
        spinnerDifficulty = findViewById(R.id.spinnerDifficulty)
        spinnerGamemode = findViewById(R.id.spinnerGamemode)
        inputMaxPlayers = findViewById(R.id.inputMaxPlayers)
        inputViewDistance = findViewById(R.id.inputViewDistance)
        inputMotd = findViewById(R.id.inputMotd)
        inputRawProperties = findViewById(R.id.inputRawProperties)
        inputRawProperties.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        spinnerDifficulty.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, difficultyOptions)
        spinnerGamemode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, gamemodeOptions)

        loadCurrentValues()

        findViewById<Button>(R.id.buttonApplyQuick).setOnClickListener { applyQuickToRaw() }
        findViewById<Button>(R.id.buttonSaveProperties).setOnClickListener { saveToFile() }
    }

    private fun loadCurrentValues() {
        val content = manager.readRaw(propertiesFile)
        inputRawProperties.setText(content)

        switchWhitelist.isChecked = manager.getValue(content, "white-list") == "true"
        switchPvp.isChecked = manager.getValue(content, "pvp") != "false"
        switchOnlineMode.isChecked = manager.getValue(content, "online-mode") != "false"

        val difficulty = manager.getValue(content, "difficulty") ?: "easy"
        spinnerDifficulty.setSelection(difficultyOptions.indexOf(difficulty).takeIf { it >= 0 } ?: 1)

        val gamemode = manager.getValue(content, "gamemode") ?: "survival"
        spinnerGamemode.setSelection(gamemodeOptions.indexOf(gamemode).takeIf { it >= 0 } ?: 0)

        inputMaxPlayers.setText(manager.getValue(content, "max-players") ?: "20")
        inputViewDistance.setText(manager.getValue(content, "view-distance") ?: "10")
        inputMotd.setText(manager.getValue(content, "motd") ?: "A Minecraft Server")
    }

    private fun applyQuickToRaw() {
        var content = inputRawProperties.text.toString()
        content = manager.setValue(content, "white-list", switchWhitelist.isChecked.toString())
        content = manager.setValue(content, "pvp", switchPvp.isChecked.toString())
        content = manager.setValue(content, "online-mode", switchOnlineMode.isChecked.toString())
        content = manager.setValue(content, "difficulty", difficultyOptions[spinnerDifficulty.selectedItemPosition])
        content = manager.setValue(content, "gamemode", gamemodeOptions[spinnerGamemode.selectedItemPosition])
        content = manager.setValue(content, "max-players", inputMaxPlayers.text.toString().ifBlank { "20" })
        content = manager.setValue(content, "view-distance", inputViewDistance.text.toString().ifBlank { "10" })
        content = manager.setValue(content, "motd", inputMotd.text.toString())
        inputRawProperties.setText(content)
        Toast.makeText(this, getString(R.string.props_applied), Toast.LENGTH_SHORT).show()
    }

    private fun saveToFile() {
        if (isSaving) return
        isSaving = true

        if (MinecraftServerService.isRunning(serverIdForCheck)) {
            isSaving = false
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.props_server_running))
                .setMessage(getString(R.string.props_stop_first))
                .setPositiveButton(getString(R.string.understood), null)
                .show()
            return
        }

        val content = inputRawProperties.text.toString()

        if (content.isBlank() || content.length < 20) {
            isSaving = false
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.props_suspicious))
                .setMessage(getString(R.string.props_suspicious_message))
                .setPositiveButton(getString(R.string.props_save_anyway)) { _, _ ->
                    manager.writeRaw(propertiesFile, content)
                    Toast.makeText(this, getString(R.string.props_saved), Toast.LENGTH_LONG).show()
                    finish()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
            return
        }

        try {
            manager.writeRaw(propertiesFile, content)
            Toast.makeText(this, getString(R.string.props_saved_ok), Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.props_save_error))
                .setMessage(e.message ?: getString(R.string.unknown_error))
                .setPositiveButton(getString(R.string.ok), null)
                .show()
        } finally {
            isSaving = false
        }
    }
}