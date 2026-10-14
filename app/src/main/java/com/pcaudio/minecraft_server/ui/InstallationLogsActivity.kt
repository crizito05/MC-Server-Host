package com.pcaudio.minecraft_server.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pcaudio.minecraft_server.R
import java.io.File

class InstallationLogsActivity : AppCompatActivity() {

    private lateinit var logsDir: File
    private lateinit var adapter: LogSessionAdapter
    private lateinit var recyclerLogs: RecyclerView
    private lateinit var textEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_installation_logs)
        title = getString(R.string.logs_installation_title)

        logsDir = File(filesDir, "creation_logs")
        recyclerLogs = findViewById(R.id.recyclerInstallLogs)
        textEmpty = findViewById(R.id.textEmptyInstallLogs)

        recyclerLogs.layoutManager = LinearLayoutManager(this)
        adapter = LogSessionAdapter(emptyList(), { file -> showLogContent(file) }, { file -> deleteLog(file) })
        recyclerLogs.adapter = adapter

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val files = logsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        adapter.updateFiles(files)
        textEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        recyclerLogs.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showLogContent(file: File) {
        val content = try { file.readText() } catch (e: Exception) { getString(R.string.logs_read_error, e.message) }

        val scrollView = android.widget.ScrollView(this)
        val textView = TextView(this)
        textView.text = content
        textView.textSize = 11f
        textView.typeface = android.graphics.Typeface.MONOSPACE
        textView.setTextIsSelectable(true)
        val padding = (16 * resources.displayMetrics.density).toInt()
        textView.setPadding(padding, padding, padding, padding)
        scrollView.addView(textView)

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(scrollView)
            .setPositiveButton(R.string.logs_close, null)
            .show()
    }

    private fun deleteLog(file: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.logs_delete_confirm)
            .setPositiveButton(R.string.logs_delete) { _, _ ->
                file.delete()
                refreshList()
            }
            .setNegativeButton(R.string.logs_cancel, null)
            .show()
    }
}