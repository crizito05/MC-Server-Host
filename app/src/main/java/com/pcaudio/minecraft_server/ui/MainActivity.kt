package com.pcaudio.minecraft_server.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
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
import com.pcaudio.minecraft_server.core.AppLanguage
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

        findViewById<Button>(R.id.buttonNetwork).setOnClickListener {
            startActivity(Intent(this, NetworkActivity::class.java))
        }

        findViewById<Button>(R.id.buttonInstallLogs).setOnClickListener {
            startActivity(Intent(this, InstallationLogsActivity::class.java))
        }

        requestNotificationPermissionIfNeeded()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.actionToggleLanguage)?.title = if (AppLanguage.isEnglish()) "Italiano" else "English"
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.actionToggleLanguage) {
            AppLanguage.toggle()
            return true
        }
        return super.onOptionsItemSelected(item)
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
        nameInput.hint = getString(R.string.edit_name_hint)
        nameInput.setText(server.name)
        container.addView(nameInput)

        val ramInfoText = TextView(this)
        ramInfoText.text = getString(R.string.edit_ram_total, totalRam, recommendedRam)
        ramInfoText.textSize = 12f
        ramInfoText.setPadding(0, padding, 0, 0)
        container.addView(ramInfoText)

        val ramInput = EditText(this)
        ramInput.hint = getString(R.string.edit_ram_hint)
        ramInput.inputType = InputType.TYPE_CLASS_NUMBER
        ramInput.setText(server.ramMb.toString())
        container.addView(ramInput)

        val optimizeButton = Button(this)
        optimizeButton.text = getString(R.string.edit_optimize)
        container.addView(optimizeButton)

        optimizeButton.setOnClickListener {
            optimizeButton.isEnabled = false
            Thread {
                val optimizer = com.pcaudio.minecraft_server.core.RamOptimizer(this)
                val result = optimizer.optimize { }
                runOnUiThread {
                    ramInfoText.text = getString(R.string.edit_ram_available, totalRam, result.newAvailableRamMb)
                    optimizeButton.isEnabled = true
                }
            }.start()
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.edit_title))
            .setView(container)
            .setPositiveButton(getString(R.string.edit_save)) { _, _ ->
                val newName = nameInput.text.toString().trim()
                val newRam = ramInput.text.toString().toIntOrNull() ?: server.ramMb

                if (newRam > totalRam) {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.edit_ram_error_title))
                        .setMessage(getString(R.string.edit_ram_error_message, totalRam))
                        .setPositiveButton(getString(R.string.understood), null)
                        .show()
                    return@setPositiveButton
                }

                if (newName.isNotBlank()) {
                    repository.saveServer(server.copy(name = newName, ramMb = newRam))
                    refreshServerList()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun onDeleteClicked(server: ServerConfig) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_title))
            .setMessage(getString(R.string.delete_message, server.name))
            .setPositiveButton(getString(R.string.delete_confirm)) { _, _ ->
                deletionManager.deleteServer(server.id) { success, error ->
                    runOnUiThread {
                        if (!success) {
                            AlertDialog.Builder(this)
                                .setTitle(getString(R.string.delete_error_title))
                                .setMessage(error ?: getString(R.string.unknown_error))
                                .setPositiveButton(getString(R.string.ok), null)
                                .show()
                        }
                        refreshServerList()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
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