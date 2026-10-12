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
        title = "Mod installate: ${config.name}"

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
            textEmptyInstalled.text = "Nessuna mod installata. Gli aggiornamenti non sono disponibili sui server Vanilla."
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
                Toast.makeText(this, "$modTitle aggiornata. Riavvia il server per applicare la modifica.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Aggiornamento di $modTitle non riuscito: ${error ?: "errore sconosciuto"}", Toast.LENGTH_LONG).show()
            }
            refreshList()
            return
        }
        if (success) {
            Toast.makeText(this, "$modTitle installata", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Installazione di $modTitle non riuscita: ${error ?: "errore sconosciuto"}", Toast.LENGTH_LONG).show()
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
            buttonUpdateAll.text = "Aggiornamento..."
            return
        }
        val available = updateEntries.count {
            it.status == ModUpdateChecker.STATUS_UPDATE_AVAILABLE && !it.locked
        }
        buttonUpdateAll.text = if (available > 0) "Aggiorna tutto ($available)" else "Aggiorna tutto"
        buttonUpdateAll.isEnabled = available > 0 && !checkingUpdates
    }

    private fun checkUpdates() {
        if (checkingUpdates || updateBatchRunning) return
        if (config.loader == ModLoader.VANILLA) {
            Toast.makeText(this, "Gli aggiornamenti non sono disponibili sui server Vanilla", Toast.LENGTH_LONG).show()
            return
        }
        val files = modsDir.listFiles()
            ?.filter { it.name.endsWith(".jar") && !it.name.endsWith(ModUpdateChecker.PART_SUFFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (files.isEmpty()) {
            Toast.makeText(this, "Nessuna mod installata", Toast.LENGTH_SHORT).show()
            return
        }
        checkingUpdates = true
        buttonCheckUpdates.isEnabled = false
        buttonCheckUpdates.text = "Verifica in corso..."
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
                buttonCheckUpdates.text = "Verifica aggiornamenti"
                updateUpdateAllButtonState()
                val message = when {
                    available > 0 && blocked > 0 -> "$available aggiornamenti disponibili, $blocked mod con versione bloccata"
                    available > 0 -> "$available aggiornamenti disponibili"
                    blocked > 0 -> "Nessun aggiornamento. $blocked mod con versione bloccata"
                    else -> "Nessun aggiornamento disponibile"
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
            Toast.makeText(this, "Nessuna mod sbloccata da aggiornare", Toast.LENGTH_LONG).show()
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
        buttonUpdateAll.text = "Aggiornamento $index di $updateBatchTotal..."
        startUpdate(entry, true)
    }

    private fun finishBatch() {
        updateBatchRunning = false
        updateBatchTotal = 0
        updateQueue.clear()
        buttonCheckUpdates.isEnabled = true
        val message = when {
            updateBatchFailed == 0 -> "$updateBatchSuccess mod aggiornate. Riavvia il server per applicare le modifiche."
            updateBatchSuccess == 0 -> "Nessuna mod aggiornata: $updateBatchFailed tentativi non riusciti"
            else -> "$updateBatchSuccess mod aggiornate, $updateBatchFailed non riuscite. Riavvia il server per applicare le modifiche."
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
            .setTitle("Rimuovere questa mod?")
            .setMessage("${file.name} verrà eliminata. Ricorda di riavviare il server per applicare la modifica.")
            .setPositiveButton("Rimuovi") { _, _ ->
                file.delete()
                refreshList()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showModOptions(entry: ModUpdateEntry) {
        if (entry.sourceProvider == null || entry.projectId == null) {
            AlertDialog.Builder(this)
                .setTitle(entry.file.name)
                .setMessage("Questa mod non ha un'origine registrata, quindi non può essere aggiornata né bloccata. Puoi comunque rimuoverla.")
                .setPositiveButton("Ho capito", null)
                .show()
            return
        }

        val key = "${entry.sourceProvider}:${entry.projectId}"
        val builder = AlertDialog.Builder(this).setTitle(entry.file.name)

        if (entry.locked) {
            val pinned = pinStore.getPinned(key)
            builder.setMessage("Versione bloccata: ${pinned?.versionNumber ?: entry.file.name}. Questa mod non verrà aggiornata finché non la sblocchi.")
            builder.setPositiveButton("Sblocca aggiornamenti") { _, _ ->
                pinStore.unpin(key)
                Toast.makeText(this, "Aggiornamenti sbloccati", Toast.LENGTH_SHORT).show()
                refreshList()
            }
        } else {
            builder.setMessage("Bloccando la versione, questa mod resterà così com'è e la verifica degli aggiornamenti la ignorerà, finché non la sblocchi.")
            builder.setPositiveButton("Blocca versione") { _, _ ->
                pinStore.pin(key, entry.file.name, entry.newest?.versionNumber ?: "")
                Toast.makeText(this, "Versione bloccata", Toast.LENGTH_SHORT).show()
                refreshList()
            }
        }
        builder.setNegativeButton("Annulla", null)
        builder.show()
    }

    private fun startUpdate(entry: ModUpdateEntry, isBatch: Boolean) {
        val newest = entry.newest
        val projectId = entry.projectId
        val provider = entry.sourceProvider
        if (newest == null || projectId == null || provider == null) {
            Toast.makeText(this, "Aggiornamento non disponibile per ${entry.file.name}", Toast.LENGTH_LONG).show()
            if (isBatch && updateBatchRunning) {
                updateBatchFailed++
                if (updateQueue.isNotEmpty()) startNextInQueue() else finishBatch()
            }
            return
        }
        if (newest.downloadUrl.isBlank()) {
            Toast.makeText(this, "L'autore di ${entry.file.name} non consente il download da applicazioni esterne", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Aggiornamento di ${entry.file.name} in corso", Toast.LENGTH_SHORT).show()
        }
    }
}