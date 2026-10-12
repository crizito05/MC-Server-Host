package com.pcaudio.minecraft_server.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pcaudio.minecraft_server.R
import com.pcaudio.minecraft_server.core.ServerConfig
import com.pcaudio.minecraft_server.core.ServerDeletionManager
import com.pcaudio.minecraft_server.core.ServerRepository

class MainActivity : AppCompatActivity() {

    private lateinit var repository: ServerRepository
    private lateinit var deletionManager: ServerDeletionManager
    private lateinit var adapter: ServerListAdapter
    private lateinit var recyclerServers: RecyclerView
    private lateinit var textEmptyState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = ServerRepository(this)
        deletionManager = ServerDeletionManager(this)

        recyclerServers = findViewById(R.id.recyclerServers)
        textEmptyState = findViewById(R.id.textEmptyState)

        recyclerServers.layoutManager = LinearLayoutManager(this)
        adapter = ServerListAdapter(
            servers = emptyList(),
            onOpen = { server -> openServerDetail(server) },
            onEdit = { server -> showEditDialog(server) },
            onDelete = { server -> onDeleteClicked(server) }
        )
        recyclerServers.adapter = adapter

        findViewById<Button>(R.id.buttonNewServer).setOnClickListener {
            val isRooted = com.pcaudio.minecraft_server.core.RootChecker().isDeviceRooted()
            val intent = Intent(this, CreateServerActivity::class.java)
            intent.putExtra("use_root", isRooted)
            startActivity(intent)
        }

        findViewById<Button>(R.id.buttonInstallLogs).setOnClickListener {
            startActivity(Intent(this, InstallationLogsActivity::class.java))
        }

        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshServerList()
    }

    private fun refreshServerList() {
        val servers = repository.listServers()
        adapter.updateServers(servers)
        textEmptyState.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
        recyclerServers.visibility = if (servers.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openServerDetail(server: ServerConfig) {
        val intent = Intent(this, ServerDetailActivity::class.java)
        intent.putExtra("server_id", server.id)
        startActivity(intent)
    }

    private fun showEditDialog(server: ServerConfig) {
        val memoryInfo = com.pcaudio.minecraft_server.core.DeviceMemoryInfo(this)
        val totalRam = memoryInfo.getTotalRamMb()
        val recommendedRam = memoryInfo.getRecommendedMaxRamMb()

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        container.setPadding(padding, padding, padding, padding)

        val nameInput = EditText(this)
        nameInput.hint = "Nome"
        nameInput.setText(server.name)
        container.addView(nameInput)

        val ramInfoText = TextView(this)
        ramInfoText.text = "RAM totale: ${totalRam}MB — consigliata fino a ${recommendedRam}MB"
        ramInfoText.textSize = 12f
        ramInfoText.setPadding(0, padding, 0, 0)
        container.addView(ramInfoText)

        val ramInput = EditText(this)
        ramInput.hint = "RAM (MB)"
        ramInput.inputType = InputType.TYPE_CLASS_NUMBER
        ramInput.setText(server.ramMb.toString())
        container.addView(ramInput)

        val optimizeButton = Button(this)
        optimizeButton.text = "Ottimizza RAM"
        container.addView(optimizeButton)

        optimizeButton.setOnClickListener {
            optimizeButton.isEnabled = false
            Thread {
                val optimizer = com.pcaudio.minecraft_server.core.RamOptimizer(this)
                val result = optimizer.optimize { }
                runOnUiThread {
                    ramInfoText.text = "RAM totale: ${totalRam}MB — disponibile ora: ${result.newAvailableRamMb}MB"
                    optimizeButton.isEnabled = true
                }
            }.start()
        }

        AlertDialog.Builder(this)
            .setTitle("Modifica server")
            .setView(container)
            .setPositiveButton("Salva") { _, _ ->
                val newName = nameInput.text.toString().trim()
                val newRam = ramInput.text.toString().toIntOrNull() ?: server.ramMb

                if (newRam > totalRam) {
                    AlertDialog.Builder(this)
                        .setTitle("RAM non disponibile")
                        .setMessage("Il dispositivo ha solo ${totalRam}MB di RAM totale.")
                        .setPositiveButton("Ho capito", null)
                        .show()
                    return@setPositiveButton
                }

                if (newName.isNotBlank()) {
                    repository.saveServer(server.copy(name = newName, ramMb = newRam))
                    refreshServerList()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun onDeleteClicked(server: ServerConfig) {
        AlertDialog.Builder(this)
            .setTitle("Eliminare il server?")
            .setMessage("Questa azione cancella definitivamente ${server.name}, incluso il mondo salvato e tutte le mod. Non è recuperabile.")
            .setPositiveButton("Elimina") { _, _ ->
                deletionManager.deleteServer(server.id) { success, error ->
                    runOnUiThread {
                        if (!success) {
                            AlertDialog.Builder(this)
                                .setTitle("Errore eliminazione")
                                .setMessage(error ?: "Errore sconosciuto")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                        refreshServerList()
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }
}