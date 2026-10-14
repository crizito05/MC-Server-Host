package com.pcaudio.minecraft_server.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pcaudio.minecraft_server.R
import com.pcaudio.minecraft_server.core.ApiKeyStore
import com.pcaudio.minecraft_server.core.CurseForgeApi
import com.pcaudio.minecraft_server.core.InstalledModsTracker
import com.pcaudio.minecraft_server.core.ModCatalog
import com.pcaudio.minecraft_server.core.ModLoader
import com.pcaudio.minecraft_server.core.ModPinStore
import com.pcaudio.minecraft_server.core.ModUpdateChecker
import com.pcaudio.minecraft_server.core.ModUpdateEntry
import com.pcaudio.minecraft_server.core.ModrinthApi
import com.pcaudio.minecraft_server.core.ServerConfig
import com.pcaudio.minecraft_server.core.ServerRepository
import com.pcaudio.minecraft_server.service.ModInstallService
import java.io.File

class InstalledModsActivity : AppCompatActivity(), (Boolean, String, String?, Boolean) -> Unit {

    private lateinit var config: ServerConfig
    private lateinit var serverDir: File
    private lateinit var modsDir: File
    private lateinit var tracker: InstalledModsTracker
    private lateinit var pinStore: ModPinStore
    private lateinit var catalog: ModCatalog
    private lateinit var adapter: InstalledModAdapter
    private lateinit var recyclerInstalledMods: RecyclerView
    private lateinit var textEmptyInstalled: android.widget.TextView
    private lateinit var buttonCheckUpdates: Button
    private lateinit var buttonUpdateAll: Button
    private var checkingUpdates = false
    private var updateEntries: List<ModUpdateEntry> = emptyList()
    private var lastEntries: Map<String, ModUpdateEntry> = emptyMap()
    private val updateQueue = ArrayDeque<ModUpdateEntry>()
    private var updateBatchRunning = false
    private var updateBatchTotal = 0
    private var updateBatchSuccess = 0
    private var updateBatchFailed = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_installed_mods)

        val serverId = intent.getStringExtra("server_id")
        val repository = ServerRepository(this)
        val found = repository.listServers().firstOrNull { it.id == serverId }
        if (found == null) {
            finish()
            return
        }
        config = found
        serverDir = File(config.directoryPath)
        modsDir = File(serverDir, "mods")
        tracker = InstalledModsTracker(serverDir)
        pinStore = ModPinStore(serverDir)
        title = getString(R.string.installed_title, config.name)

        val curseForgeKey = ApiKeyStore(this).getCurseForgeKey()
        catalog = ModCatalog(
            modrinth = ModrinthApi(),
            curseForge = if (curseForgeKey != null) CurseForgeApi(curseForgeKey) else null
        )

        recyclerInstalledMods = findViewById(R.id.recyclerInstalledMods)
        textEmptyInstalled = findViewById(R.id.textEmptyInstalled)
        buttonCheckUpdates = findViewById(R.id.buttonCheckUpdates)
        buttonUpdateAll = findViewById(R.id.buttonUpdateAll)

        recyclerInstalledMods.layoutManager = LinearLayoutManager(this)
        adapter = InstalledModAdapter(
            emptyList(),
            { file -> confirmRemove(file) },
            { entry -> startUpdate(entry, false) },
            { entry -> showModOptions(entry) }
        )
        recyclerInstalledMods.adapter = adapter

        if (config.loader == ModLoader.VANILLA) {
            buttonCheckUpdates.visibility = View.GONE
            buttonUpdateAll.visibility = View.GONE
            textEmptyInstalled.text = getString(R.string.installed_empty_vanilla)
        }

        buttonCheckUpdates.setOnClickListener { checkUpdates() }
        buttonUpdateAll.setOnClickListener { updateAll() }
        buttonUpdateAll.isEnabled = false

        refreshList()
    }

    override fun onStart() {
        super.onStart()
        ModInstallService.addResultListener(this)
    }

    override fun onStop() {
        super.onStop()
        ModInstallService.removeResultListener(this)
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    override fun invoke(success: Boolean, modTitle: String, error: String?, isUpdate: Boolean) {
        runOnUiThread { onModOperationResult(success, modTitle, error, isUpdate) }
    }

    private fun onModOperationResult(success: Boolean, modTitle: String, error: String?, isUpdate: Boolean) {
        if (isUpdate && updateBatchRunning) {
            if (success) updateBatchSuccess++ else updateBatchFailed++
            if (updateQueue.isNotEmpty()) {
                startNextInQueue()
                return
            }
            finishBatch()
            return
        }
        if (isUpdate) {
            if (success) {
                Toast.makeText(this, getString(R.string.installed_toast_updated, modTitle), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.installed_toast_update_failed, modTitle, error ?: getString(R.string.ui_unknown_error)), Toast.LENGTH_LONG).show()
            }
            refreshList()
            return
        }
        if (success) {
            Toast.makeText(this, getString(R.string.installed_toast_installed, modTitle), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.installed_toast_install_failed, modTitle, error ?: getString(R.string.ui_unknown_error)), Toast.LENGTH_LONG).show()
        }
        refreshList()
    }

    private fun refreshList() {
        val files = modsDir.listFiles()
            ?.filter { it.name.endsWith(".jar") && !it.name.endsWith(ModUpdateChecker.PART_SUFFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()

        val tracked = tracker.listTracked()
        val entries = files.map { file ->
            val cached = lastEntries[file.name]
            val record = tracked.firstOrNull { it.filename == file.name }
            val provider = cached?.sourceProvider ?: record?.sourceProvider
            val projectId = cached?.projectId ?: record?.projectId
            val newest = cached?.newest
            val locked = if (provider != null && projectId != null) pinStore.isPinned("$provider:$projectId") else false

            val status = when {
                provider == null || projectId == null -> ModUpdateChecker.STATUS_UNKNOWN
                locked -> ModUpdateChecker.STATUS_BLOCKED
                cached == null -> ModUpdateChecker.STATUS_UNKNOWN
                cached.status == ModUpdateChecker.STATUS_BLOCKED ->
                    if (newest != null && newest.filename != file.name) ModUpdateChecker.STATUS_UPDATE_AVAILABLE
                    else ModUpdateChecker.STATUS_UP_TO_DATE
                else -> cached.status
            }
            ModUpdateEntry(file, provider, projectId, status, newest, locked)
        }

        updateEntries = entries
        lastEntries = entries.associateBy { it.file.name }
        adapter.updateEntries(entries)
        textEmptyInstalled.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        recyclerInstalledMods.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        updateUpdateAllButtonState()
    }

    private fun updateUpdateAllButtonState() {
        if (config.loader == ModLoader.VANILLA) return
        if (updateBatchRunning) {
            buttonUpdateAll.isEnabled = false
            buttonUpdateAll.text = getString(R.string.installed_button_updating)
            return
        }
        val available = updateEntries.count {
            it.status == ModUpdateChecker.STATUS_UPDATE_AVAILABLE && !it.locked
        }
        buttonUpdateAll.text = if (available > 0) getString(R.string.installed_button_update_all_count, available) else getString(R.string.installed_button_update_all)
        buttonUpdateAll.isEnabled = available > 0 && !checkingUpdates
    }

    private fun checkUpdates() {
        if (checkingUpdates || updateBatchRunning) return
        if (config.loader == ModLoader.VANILLA) {
            Toast.makeText(this, R.string.installed_toast_vanilla, Toast.LENGTH_LONG).show()
            return
        }
        val files = modsDir.listFiles()
            ?.filter { it.name.endsWith(".jar") && !it.name.endsWith(ModUpdateChecker.PART_SUFFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.installed_empty, Toast.LENGTH_SHORT).show()
            return
        }
        checkingUpdates = true
        buttonCheckUpdates.isEnabled = false
        buttonCheckUpdates.text = getString(R.string.installed_button_checking)
        updateUpdateAllButtonState()

        Thread {
            val entries = try {
                ModUpdateChecker().check(files, tracker, pinStore, catalog, config.loader, config.minecraftVersion)
            } catch (e: Exception) {
                files.map { file ->
                    ModUpdateEntry(file, null, null, ModUpdateChecker.STATUS_UNKNOWN, null, false)
                }
            }
            val available = entries.count { it.status == ModUpdateChecker.STATUS_UPDATE_AVAILABLE }
            val blocked = entries.count { it.status == ModUpdateChecker.STATUS_BLOCKED }
            runOnUiThread {
                updateEntries = entries
                lastEntries = entries.associateBy { it.file.name }
                adapter.updateEntries(entries)
                checkingUpdates = false
                buttonCheckUpdates.isEnabled = true
                buttonCheckUpdates.text = getString(R.string.installed_button_check)
                updateUpdateAllButtonState()
                val message = when {
                    available > 0 && blocked > 0 -> getString(R.string.installed_updates_and_blocked, available, blocked)
                    available > 0 -> getString(R.string.installed_updates_available, available)
                    blocked > 0 -> getString(R.string.installed_no_updates_blocked, blocked)
                    else -> getString(R.string.installed_no_updates)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun updateAll() {
        if (updateBatchRunning || checkingUpdates) return
        val targets = updateEntries.filter {
            it.status == ModUpdateChecker.STATUS_UPDATE_AVAILABLE && !it.locked
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.installed_toast_none_unlocked, Toast.LENGTH_LONG).show()
            return
        }
        val needsCurseForgeKey = targets.any { it.sourceProvider == "CurseForge" }
        if (needsCurseForgeKey && !ApiKeyStore(this).hasCurseForgeKey()) {
            ApiKeyDialogs.showCurseForgeMissingKeyDialog(this) {
                val newKey = ApiKeyStore(this).getCurseForgeKey()
                if (newKey != null) {
                    catalog = ModCatalog(modrinth = ModrinthApi(), curseForge = CurseForgeApi(newKey))
                    checkUpdates()
                }
            }
            return
        }

        updateQueue.clear()
        updateQueue.addAll(targets)
        updateBatchRunning = true
        updateBatchTotal = targets.size
        updateBatchSuccess = 0
        updateBatchFailed = 0
        buttonCheckUpdates.isEnabled = false
        updateUpdateAllButtonState()
        startNextInQueue()
    }

    private fun startNextInQueue() {
        val entry = updateQueue.removeFirst()
        val index = updateBatchTotal - updateQueue.size
        buttonUpdateAll.text = getString(R.string.installed_button_updating_index, index, updateBatchTotal)
        startUpdate(entry, true)
    }

    private fun finishBatch() {
        updateBatchRunning = false
        updateBatchTotal = 0
        updateQueue.clear()
        buttonCheckUpdates.isEnabled = true
        val message = when {
            updateBatchFailed == 0 -> getString(R.string.installed_batch_all_ok, updateBatchSuccess)
            updateBatchSuccess == 0 -> getString(R.string.installed_batch_none, updateBatchFailed)
            else -> getString(R.string.installed_batch_partial, updateBatchSuccess, updateBatchFailed)
        }
        updateBatchSuccess = 0
        updateBatchFailed = 0
        if (!isFinishing && !isDestroyed) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            refreshList()
            checkUpdates()
        }
    }

    private fun confirmRemove(file: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.installed_remove_title)
            .setMessage(getString(R.string.installed_remove_message, file.name))
            .setPositiveButton(R.string.installed_remove_button) { _, _ ->
                file.delete()
                refreshList()
            }
            .setNegativeButton(R.string.ui_cancel, null)
            .show()
    }

    private fun showModOptions(entry: ModUpdateEntry) {
        if (entry.sourceProvider == null || entry.projectId == null) {
            AlertDialog.Builder(this)
                .setTitle(entry.file.name)
                .setMessage(R.string.installed_no_origin_message)
                .setPositiveButton(R.string.ui_understood, null)
                .show()
            return
        }

        val key = "${entry.sourceProvider}:${entry.projectId}"
        val builder = AlertDialog.Builder(this).setTitle(entry.file.name)

        if (entry.locked) {
            val pinned = pinStore.getPinned(key)
            builder.setMessage(getString(R.string.installed_pinned_message, pinned?.versionNumber ?: entry.file.name))
            builder.setPositiveButton(R.string.installed_button_unpin) { _, _ ->
                pinStore.unpin(key)
                Toast.makeText(this, R.string.installed_toast_unpinned, Toast.LENGTH_SHORT).show()
                refreshList()
            }
        } else {
            builder.setMessage(R.string.installed_pin_message)
            builder.setPositiveButton(R.string.installed_button_pin) { _, _ ->
                pinStore.pin(key, entry.file.name, entry.newest?.versionNumber ?: "")
                Toast.makeText(this, R.string.installed_toast_pinned, Toast.LENGTH_SHORT).show()
                refreshList()
            }
        }
        builder.setNegativeButton(R.string.ui_cancel, null)
        builder.show()
    }

    private fun startUpdate(entry: ModUpdateEntry, isBatch: Boolean) {
        val newest = entry.newest
        val projectId = entry.projectId
        val provider = entry.sourceProvider
        if (newest == null || projectId == null || provider == null) {
            Toast.makeText(this, getString(R.string.installed_toast_update_unavailable, entry.file.name), Toast.LENGTH_LONG).show()
            if (isBatch && updateBatchRunning) {
                updateBatchFailed++
                if (updateQueue.isNotEmpty()) startNextInQueue() else finishBatch()
            }
            return
        }
        if (newest.downloadUrl.isBlank()) {
            Toast.makeText(this, getString(R.string.installed_toast_download_blocked, entry.file.name), Toast.LENGTH_LONG).show()
            if (isBatch && updateBatchRunning) {
                updateBatchFailed++
                if (updateQueue.isNotEmpty()) startNextInQueue() else finishBatch()
            }
            return
        }

        val intent = Intent(this, ModInstallService::class.java)
        intent.putExtra("mod_title", entry.file.name)
        intent.putExtra("project_id", projectId)
        intent.putExtra("source_provider", provider)
        intent.putExtra("loader", config.loader.name)
        intent.putExtra("minecraft_version", config.minecraftVersion)
        intent.putExtra("mods_dir", modsDir.absolutePath)
        intent.putExtra("server_dir", serverDir.absolutePath)
        intent.putExtra("chosen_filename", newest.filename)
        intent.putExtra("chosen_download_url", newest.downloadUrl)
        intent.putExtra("curseforge_key", ApiKeyStore(this).getCurseForgeKey())
        intent.putExtra("old_filename", entry.file.name)
        intent.putExtra(ModInstallService.EXTRA_IS_UPDATE, true)
        intent.putStringArrayListExtra("required_deps", ArrayList(newest.requiredDependencyProjectIds))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        if (!isBatch) {
            Toast.makeText(this, getString(R.string.installed_toast_update_started, entry.file.name), Toast.LENGTH_SHORT).show()
        }
    }
}