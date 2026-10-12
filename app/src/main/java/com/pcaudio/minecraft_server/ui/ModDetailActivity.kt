package com.pcaudio.minecraft_server.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pcaudio.minecraft_server.R
import com.pcaudio.minecraft_server.core.ApiKeyStore
import com.pcaudio.minecraft_server.core.CurseForgeApi
import com.pcaudio.minecraft_server.core.ModCatalog
import com.pcaudio.minecraft_server.core.ModLoader
import com.pcaudio.minecraft_server.core.ModVersionFile
import com.pcaudio.minecraft_server.core.ModrinthApi
import com.pcaudio.minecraft_server.core.ServerRepository
import com.pcaudio.minecraft_server.service.ModInstallService
import java.io.File

class ModDetailActivity : AppCompatActivity() {

    private lateinit var serverDir: File
    private lateinit var loader: ModLoader
    private lateinit var minecraftVersion: String
    private lateinit var catalog: ModCatalog

    private lateinit var projectId: String
    private lateinit var sourceProvider: String
    private lateinit var modTitle: String

    private lateinit var scrollDescription: View
    private lateinit var recyclerVersions: RecyclerView
    private lateinit var textDescriptionContent: TextView
    private lateinit var versionAdapter: ModVersionAdapter

    private var loadedVersions: List<ModVersionFile> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mod_detail)

        val serverId = intent.getStringExtra("server_id")
        val repository = ServerRepository(this)
        val config = repository.listServers().firstOrNull { it.id == serverId }
        if (config == null) {
            finish()
            return
        }
        serverDir = File(config.directoryPath)
        loader = config.loader
        minecraftVersion = config.minecraftVersion

        projectId = intent.getStringExtra("project_id") ?: run { finish(); return }
        sourceProvider = intent.getStringExtra("source_provider") ?: run { finish(); return }
        modTitle = intent.getStringExtra("mod_title") ?: ""
        val downloads = intent.getIntExtra("downloads", 0)

        val curseForgeKey = ApiKeyStore(this).getCurseForgeKey()
        catalog = ModCatalog(
            modrinth = ModrinthApi(),
            curseForge = if (curseForgeKey != null) CurseForgeApi(curseForgeKey) else null
        )

        findViewById<TextView>(R.id.textDetailTitle).text = modTitle
        findViewById<TextView>(R.id.textDetailDownloads).text = "$downloads download · $sourceProvider"

        scrollDescription = findViewById(R.id.scrollDescription)
        recyclerVersions = findViewById(R.id.recyclerVersions)
        textDescriptionContent = findViewById(R.id.textDescriptionContent)

        recyclerVersions.layoutManager = LinearLayoutManager(this)
        versionAdapter = ModVersionAdapter(emptyList(), loader) { version -> confirmInstall(version) }
        recyclerVersions.adapter = versionAdapter

        findViewById<Button>(R.id.buttonTabDescription).setOnClickListener { showDescriptionTab() }
        findViewById<Button>(R.id.buttonTabVersions).setOnClickListener { showVersionsTab() }
        findViewById<Button>(R.id.buttonInstallLatest).setOnClickListener { installLatest() }

        loadDescription()
        loadVersions()
        showDescriptionTab()
    }

    private fun showDescriptionTab() {
        scrollDescription.visibility = View.VISIBLE
        recyclerVersions.visibility = View.GONE
    }

    private fun showVersionsTab() {
        scrollDescription.visibility = View.GONE
        recyclerVersions.visibility = View.VISIBLE
    }

    private fun loadDescription() {
        textDescriptionContent.text = "Caricamento descrizione..."
        Thread {
            val description = try {
                catalog.getDescription(sourceProvider, projectId)
            } catch (e: Exception) {
                "Descrizione non disponibile"
            }
            runOnUiThread { textDescriptionContent.text = description }
        }.start()
    }

    private fun loadVersions() {
        Thread {
            val versions = try {
                catalog.getVersions(sourceProvider, projectId, loader, minecraftVersion)
            } catch (e: Exception) {
                emptyList<ModVersionFile>()
            }
            loadedVersions = versions
            runOnUiThread { versionAdapter.updateVersions(versions) }
        }.start()
    }

    private fun installLatest() {
        if (loadedVersions.isEmpty()) {
            Toast.makeText(this, "Versioni non ancora caricate, riprova tra poco", Toast.LENGTH_SHORT).show()
            return
        }
        confirmInstall(loadedVersions.first())
    }

    private fun checkEnvironmentCompatibility() {
        Thread {
            val (clientSide, serverSide) = try {
                catalog.getEnvironmentInfo(sourceProvider, projectId)
            } catch (e: Exception) {
                Pair("unknown", "unknown")
            }

            runOnUiThread {
                val warningView = findViewById<TextView>(R.id.textEnvironmentWarning)
                if (serverSide == "unsupported") {
                    warningView.text = "⚠ Questa mod è solo lato client, non ha effetto su un server dedicato"
                    warningView.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun confirmInstall(versionFile: ModVersionFile) {
        val intent = Intent(this, ModInstallService::class.java)
        intent.putExtra("mod_title", modTitle)
        intent.putExtra("project_id", projectId)
        intent.putExtra("source_provider", sourceProvider)
        intent.putExtra("loader", loader.name)
        intent.putExtra("minecraft_version", minecraftVersion)
        intent.putExtra("mods_dir", File(serverDir, "mods").absolutePath)
        intent.putExtra("server_dir", serverDir.absolutePath)
        intent.putExtra("chosen_filename", versionFile.filename)
        intent.putExtra("chosen_download_url", versionFile.downloadUrl)
        intent.putExtra(ModInstallService.EXTRA_IS_UPDATE, false)
        intent.putExtra("curseforge_key", ApiKeyStore(this).getCurseForgeKey())
        intent.putStringArrayListExtra("required_deps", ArrayList(versionFile.requiredDependencyProjectIds))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        val depsMsg = if (versionFile.requiredDependencyProjectIds.isNotEmpty()) {
            " (verranno installate anche ${versionFile.requiredDependencyProjectIds.size} dipendenze richieste)"
        } else ""
        Toast.makeText(this, "Installazione di $modTitle avviata$depsMsg", Toast.LENGTH_SHORT).show()
        finish()
    }
}