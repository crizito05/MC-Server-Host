package com.pcaudio.minecraft_server.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pcaudio.minecraft_server.R
import com.pcaudio.minecraft_server.core.ApiKeyStore
import com.pcaudio.minecraft_server.core.CurseForgeApi
import com.pcaudio.minecraft_server.core.InstalledModsTracker
import com.pcaudio.minecraft_server.core.ModCatalog
import com.pcaudio.minecraft_server.core.ModSearchResult
import com.pcaudio.minecraft_server.core.ModrinthApi
import com.pcaudio.minecraft_server.core.ServerRepository
import com.pcaudio.minecraft_server.service.ModInstallService
import java.io.File

class ModBrowserActivity : AppCompatActivity() {

    private lateinit var serverDir: File
    private lateinit var serverId: String
    private lateinit var loader: com.pcaudio.minecraft_server.core.ModLoader
    private lateinit var minecraftVersion: String

    private lateinit var catalog: ModCatalog
    private lateinit var tracker: InstalledModsTracker
    private lateinit var adapter: ModResultAdapter

    private lateinit var inputSearch: EditText
    private lateinit var radioProvider: RadioGroup
    private lateinit var recyclerMods: RecyclerView
    private lateinit var textEmptyResults: TextView

    private val resultListener: (Boolean, String, String?, Boolean) -> Unit = { success, modTitle, error, isUpdate ->
        runOnUiThread {
            if (success) {
                adapter.notifyDataSetChanged()
                val message = if (isUpdate) "$modTitle aggiornata" else "$modTitle installata"
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val action = if (isUpdate) "aggiornamento" else "installazione"
                android.widget.Toast.makeText(this, "Errore $action $modTitle: $error", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mod_browser)

        val sid = intent.getStringExtra("server_id")
        val repository = ServerRepository(this)
        val config = repository.listServers().firstOrNull { it.id == sid }
        if (config == null) {
            finish()
            return
        }
        serverId = sid!!
        serverDir = File(config.directoryPath)
        loader = config.loader
        minecraftVersion = config.minecraftVersion
        tracker = InstalledModsTracker(serverDir)
        title = "Mod per ${config.name}"

        val apiKeyStore = ApiKeyStore(this)
        val curseForgeKey = apiKeyStore.getCurseForgeKey()
        catalog = ModCatalog(
            modrinth = ModrinthApi(),
            curseForge = if (curseForgeKey != null) CurseForgeApi(curseForgeKey) else null
        )

        inputSearch = findViewById(R.id.inputSearch)
        radioProvider = findViewById(R.id.radioProvider)
        recyclerMods = findViewById(R.id.recyclerMods)
        textEmptyResults = findViewById(R.id.textEmptyResults)

        recyclerMods.layoutManager = LinearLayoutManager(this)
        adapter = ModResultAdapter(
            emptyList(),
            { mod -> tracker.isInstalled(mod.projectId, mod.sourceProvider) },
            { mod -> openModDetail(mod) }
        )
        recyclerMods.adapter = adapter

        findViewById<Button>(R.id.buttonSearch).setOnClickListener { performSearch() }
        performSearch()
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
    }

    override fun onStart() {
        super.onStart()
        ModInstallService.addResultListener(resultListener)
    }

    override fun onStop() {
        super.onStop()
        ModInstallService.removeResultListener(resultListener)
    }

    private fun selectedProviderName(): String {
        return if (radioProvider.checkedRadioButtonId == R.id.radioCurseForge) "CurseForge" else "Modrinth"
    }

    private fun performSearch() {
        val query = inputSearch.text.toString().trim()
        val providerName = selectedProviderName()

        if (providerName == "CurseForge" && !ApiKeyStore(this).hasCurseForgeKey()) {
            ApiKeyDialogs.showCurseForgeMissingKeyDialog(this) {
                val newKey = ApiKeyStore(this).getCurseForgeKey()
                if (newKey != null) {
                    catalog = ModCatalog(modrinth = ModrinthApi(), curseForge = CurseForgeApi(newKey))
                    performSearch()
                }
            }
            return
        }

        textEmptyResults.text = "Caricamento..."
        textEmptyResults.visibility = View.VISIBLE
        recyclerMods.visibility = View.GONE

        Thread {
            val results = try {
                catalog.searchProvider(providerName, query, loader, minecraftVersion)
            } catch (e: Exception) {
                emptyList<ModSearchResult>()
            }

            runOnUiThread {
                if (results.isEmpty()) {
                    textEmptyResults.text = "Nessun risultato trovato per questa combinazione di versione e loader"
                    textEmptyResults.visibility = View.VISIBLE
                    recyclerMods.visibility = View.GONE
                } else {
                    textEmptyResults.visibility = View.GONE
                    recyclerMods.visibility = View.VISIBLE
                    adapter.updateResults(results)
                }
            }
        }.start()
    }

    private fun openModDetail(mod: ModSearchResult) {
        val intent = Intent(this, ModDetailActivity::class.java)
        intent.putExtra("server_id", serverId)
        intent.putExtra("project_id", mod.projectId)
        intent.putExtra("source_provider", mod.sourceProvider)
        intent.putExtra("mod_title", mod.title)
        intent.putExtra("downloads", mod.downloads)
        startActivity(intent)
    }
}