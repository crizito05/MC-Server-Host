package com.pcaudio.minecraft_server.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pcaudio.minecraft_server.R
import com.pcaudio.minecraft_server.core.CreationStep
import com.pcaudio.minecraft_server.core.DeviceMemoryInfo
import com.pcaudio.minecraft_server.core.LoaderVersionApi
import com.pcaudio.minecraft_server.core.ModLoader
import com.pcaudio.minecraft_server.core.ServerCreationManager

class CreateServerActivity : AppCompatActivity() {

    private lateinit var inputServerName: EditText
    private lateinit var spinnerLoader: Spinner
    private lateinit var spinnerMinecraftVersion: Spinner
    private lateinit var spinnerLoaderVersion: Spinner
    private lateinit var inputRam: EditText
    private lateinit var textRamInfo: TextView
    private lateinit var buttonCreateServer: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var textLog: TextView

    private lateinit var logScroll: android.widget.ScrollView

    private val versionApi = LoaderVersionApi()
    private lateinit var creationManager: ServerCreationManager
    private lateinit var memoryInfo: DeviceMemoryInfo

    private lateinit var buttonOptimizeRam: Button

    private var currentMinecraftVersions: List<String> = emptyList()
    private var totalRamMb = 0
    private var recommendedMaxRamMb = 0
    private var useRoot = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_server)
        useRoot = intent.getBooleanExtra("use_root", false)
        title = getString(if (useRoot) R.string.create_title_root else R.string.create_title_no_root)

        inputServerName = findViewById(R.id.inputServerName)
        spinnerLoader = findViewById(R.id.spinnerLoader)
        spinnerMinecraftVersion = findViewById(R.id.spinnerMinecraftVersion)
        spinnerLoaderVersion = findViewById(R.id.spinnerLoaderVersion)
        inputRam = findViewById(R.id.inputRam)
        textRamInfo = findViewById(R.id.textRamInfo)
        buttonCreateServer = findViewById(R.id.buttonCreateServer)
        progressBar = findViewById(R.id.progressBar)
        textLog = findViewById(R.id.textLog)
        logScroll = findViewById(R.id.logScroll)
        logScroll.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        buttonOptimizeRam = findViewById(R.id.buttonOptimizeRam)
        buttonOptimizeRam.setOnClickListener { onOptimizeRamClicked() }

        creationManager = ServerCreationManager(this)
        memoryInfo = DeviceMemoryInfo(this)

        setupRamInfo()
        setupLoaderSpinner()
        loadMinecraftVersions()

        buttonCreateServer.setOnClickListener { onCreateClicked() }
    }

    private fun setupRamInfo() {
        totalRamMb = memoryInfo.getTotalRamMb()
        recommendedMaxRamMb = memoryInfo.getRecommendedMaxRamMb()
        textRamInfo.text = getString(R.string.create_ram_info, totalRamMb, recommendedMaxRamMb)
        inputRam.setText(minOf(2048, recommendedMaxRamMb).toString())
    }

    private fun onOptimizeRamClicked() {
        buttonOptimizeRam.isEnabled = false
        appendLog(getString(R.string.create_ram_optimizing))

        Thread {
            val optimizer = com.pcaudio.minecraft_server.core.RamOptimizer(this)
            val result = optimizer.optimize { line -> runOnUiThread { appendLog(line) } }

            runOnUiThread {
                recommendedMaxRamMb = memoryInfo.getRecommendedMaxRamMb()
                textRamInfo.text = getString(R.string.create_ram_available, totalRamMb, result.newAvailableRamMb)
                appendLog(getString(if (result.usedRoot) R.string.create_ram_done_root else R.string.create_ram_done_standard))
                buttonOptimizeRam.isEnabled = true
            }
        }.start()
    }

    private fun setupLoaderSpinner() {
        val loaderNames = ModLoader.values().map { it.name }
        spinnerLoader.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, loaderNames)
        spinnerLoader.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                onLoaderOrVersionChanged()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
    }

    private fun loadMinecraftVersions() {
        Thread {
            val versions = versionApi.getMinecraftVersions()
            currentMinecraftVersions = versions.map { it.id }.filter { isVersionSupported(it) }
            runOnUiThread {
                spinnerMinecraftVersion.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_dropdown_item, currentMinecraftVersions
                )
                spinnerMinecraftVersion.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                        onLoaderOrVersionChanged()
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                })
                onLoaderOrVersionChanged()
            }
        }.start()
    }

    private fun isVersionSupported(minecraftVersion: String): Boolean {
        val parts = minecraftVersion.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return true
        val major = parts[0]
        val minor = parts[1]
        return !(major == 1 && minor < 17)
    }

    private fun selectedLoader(): ModLoader {
        val name = spinnerLoader.selectedItem as? String ?: return ModLoader.VANILLA
        return ModLoader.valueOf(name)
    }

    private fun selectedMinecraftVersion(): String? = spinnerMinecraftVersion.selectedItem as? String

    private fun onLoaderOrVersionChanged() {
        val loader = selectedLoader()
        val mcVersion = selectedMinecraftVersion()

        if (loader == ModLoader.VANILLA || mcVersion == null) {
            spinnerLoaderVersion.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, emptyList<String>())
            spinnerLoaderVersion.visibility = View.GONE
            return
        }

        spinnerLoaderVersion.visibility = View.VISIBLE
        appendLog(getString(R.string.create_fetch_loader_versions, loader.name, mcVersion))

        Thread {
            val versions = try {
                when (loader) {
                    ModLoader.FORGE -> versionApi.getForgeVersions(mcVersion)
                    ModLoader.NEOFORGE -> versionApi.getNeoForgeVersions(mcVersion)
                    ModLoader.FABRIC -> versionApi.getFabricLoaderVersions(mcVersion)
                    ModLoader.VANILLA -> emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }

            runOnUiThread {
                spinnerLoaderVersion.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, versions)
                if (versions.isEmpty()) {
                    appendLog(getString(R.string.create_no_loader_versions))
                }
            }
        }.start()
    }

    private fun onCreateClicked() {
        val name = inputServerName.text.toString().trim()
        if (name.isBlank()) {
            appendLog(getString(R.string.create_error_name))
            return
        }

        val loader = selectedLoader()
        val mcVersion = selectedMinecraftVersion()
        if (mcVersion == null) {
            appendLog(getString(R.string.create_error_mc_version))
            return
        }

        val loaderVersion = if (loader == ModLoader.VANILLA) null else spinnerLoaderVersion.selectedItem as? String
        if (loader != ModLoader.VANILLA && loaderVersion == null) {
            appendLog(getString(R.string.create_error_loader_version))
            return
        }

        val requestedRam = inputRam.text.toString().toIntOrNull()
        if (requestedRam == null || requestedRam <= 0) {
            appendLog(getString(R.string.create_error_ram))
            return
        }

        if (requestedRam > totalRamMb) {
            AlertDialog.Builder(this)
                .setTitle(R.string.create_ram_unavailable_title)
                .setMessage(getString(R.string.create_ram_unavailable_message, totalRamMb))
                .setPositiveButton(R.string.ui_understood, null)
                .show()
            return
        }

        if (requestedRam > recommendedMaxRamMb) {
            val marginMb = totalRamMb - requestedRam
            AlertDialog.Builder(this)
                .setTitle(R.string.create_ram_high_title)
                .setMessage(getString(R.string.create_ram_high_message, requestedRam, totalRamMb, marginMb))
                .setPositiveButton(R.string.create_proceed_anyway) { _, _ -> proceedWithCreation(name, loader, mcVersion, loaderVersion, requestedRam) }
                .setNegativeButton(R.string.ui_cancel, null)
                .show()
            return
        }

        proceedWithCreation(name, loader, mcVersion, loaderVersion, requestedRam)
    }

    private fun proceedWithCreation(name: String, loader: ModLoader, mcVersion: String, loaderVersion: String?, ramMb: Int) {
        buttonCreateServer.isEnabled = false
        progressBar.visibility = View.VISIBLE
        textLog.text = ""

        creationManager.createServer(
            name = name,
            loader = loader,
            minecraftVersion = mcVersion,
            loaderVersion = loaderVersion,
            ramMb = ramMb,
            useRoot = useRoot
        ) { step ->
            runOnUiThread {
                when (step) {
                    is CreationStep.Progress -> appendLog(step.message)
                    is CreationStep.Completed -> {
                        appendLog(getString(R.string.create_created_success, step.config.directoryPath))
                        progressBar.visibility = View.GONE
                        buttonCreateServer.isEnabled = true
                        finish()
                    }
                    is CreationStep.Failed -> {
                        appendLog(getString(R.string.create_failed, step.error))
                        progressBar.visibility = View.GONE
                        buttonCreateServer.isEnabled = true
                    }
                }
            }
        }
    }

    private fun appendLog(message: String) {
        textLog.append("$message\n")
        logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}