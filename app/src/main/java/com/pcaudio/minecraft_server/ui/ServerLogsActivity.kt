package com.pcaudio.minecraft_server.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pcaudio.minecraft_server.R
import com.pcaudio.minecraft_server.core.LogAnalyzer
import com.pcaudio.minecraft_server.core.ServerRepository
import java.io.File

class ServerLogsActivity : AppCompatActivity() {

    private lateinit var logsDir: File
    private lateinit var adapter: LogSessionAdapter
    private lateinit var recyclerLogs: RecyclerView
    private lateinit var textEmptyLogs: TextView
    private val analyzer = LogAnalyzer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_logs)

        val serverId = intent.getStringExtra("server_id")
        val repository = ServerRepository(this)
        val config = repository.listServers().firstOrNull { it.id == serverId }
        if (config == null) {
            finish()
            return
        }
        logsDir = File(config.directoryPath, "app_logs")
        title = "Log: ${config.name}"

        recyclerLogs = findViewById(R.id.recyclerLogs)
        textEmptyLogs = findViewById(R.id.textEmptyLogs)

        recyclerLogs.layoutManager = LinearLayoutManager(this)
        adapter = LogSessionAdapter(emptyList(), { file -> showLogContent(file) }, { file -> deleteLog(file) })
        recyclerLogs.adapter = adapter

        findViewById<Button>(R.id.buttonOpenLatest).setOnClickListener { openLatestLog() }

        refreshLogList()
    }

    override fun onResume() {
        super.onResume()
        refreshLogList()
    }

    private fun refreshLogList() {
        val files = logsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        adapter.updateFiles(files)
        textEmptyLogs.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        recyclerLogs.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openLatestLog() {
        val latest = logsDir.listFiles()?.maxByOrNull { it.lastModified() }
        if (latest == null) {
            AlertDialog.Builder(this)
                .setTitle("Nessun log")
                .setMessage("Non è ancora stato avviato nessun server, quindi non ci sono log da mostrare.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        showLogContent(latest)
    }

    private fun showLogContent(file: File) {
        val content = try { file.readText() } catch (e: Exception) { "Impossibile leggere il file: ${e.message}" }
        val issues = analyzer.analyze(content)

        val scrollView = android.widget.ScrollView(this)
        val container = android.widget.LinearLayout(this)
        container.orientation = android.widget.LinearLayout.VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        container.setPadding(padding, padding, padding, padding)

        if (issues.isNotEmpty()) {
            val issuesTitle = TextView(this)
            issuesTitle.text = "Problemi rilevati:"
            issuesTitle.textSize = 14f
            issuesTitle.setTypeface(null, android.graphics.Typeface.BOLD)
            container.addView(issuesTitle)

            for (issue in issues.distinctBy { it.severity + it.description }) {
                val issueText = TextView(this)
                issueText.text = "• [${issue.severity}] ${issue.description}"
                issueText.textSize = 12f
                issueText.setTextColor(android.graphics.Color.RED)
                issueText.setPadding(0, 8, 0, 0)
                container.addView(issueText)
            }

            val divider = View(this)
            divider.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 2
            )
            divider.setBackgroundColor(android.graphics.Color.GRAY)
            (divider.layoutParams as android.widget.LinearLayout.LayoutParams).topMargin = padding
            (divider.layoutParams as android.widget.LinearLayout.LayoutParams).bottomMargin = padding
            container.addView(divider)
        }

        val logText = TextView(this)
        logText.text = content
        logText.textSize = 10f
        logText.typeface = android.graphics.Typeface.MONOSPACE
        logText.setTextIsSelectable(true)
        container.addView(logText)

        scrollView.addView(container)

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(scrollView)
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun deleteLog(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Eliminare questo log?")
            .setPositiveButton("Elimina") { _, _ ->
                val deleted = try {
                    file.delete()
                } catch (e: Exception) {
                    false
                }

                if (!deleted) {
                    Thread {
                        try {
                            val process = ProcessBuilder("su", "-c", "rm -f '${file.absolutePath}'").start()
                            process.waitFor()
                        } catch (e: Exception) {
                        }
                        runOnUiThread { refreshLogList() }
                    }.start()
                } else {
                    refreshLogList()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
}