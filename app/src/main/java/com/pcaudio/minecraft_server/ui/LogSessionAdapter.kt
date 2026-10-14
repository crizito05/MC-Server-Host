package com.pcaudio.minecraft_server.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pcaudio.minecraft_server.R
import com.pcaudio.minecraft_server.core.LogAnalyzer
import java.io.File

class LogSessionAdapter(
    private var logFiles: List<File>,
    private val onView: (File) -> Unit,
    private val onDelete: (File) -> Unit
) : RecyclerView.Adapter<LogSessionAdapter.LogViewHolder>() {

    class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val date: TextView = view.findViewById(R.id.textLogDate)
        val summary: TextView = view.findViewById(R.id.textLogSummary)
        val view: Button = view.findViewById(R.id.buttonViewLog)
        val delete: Button = view.findViewById(R.id.buttonDeleteLog)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log_session, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val file = logFiles[position]
        holder.date.text = file.name.removePrefix("session_").removeSuffix(".log").replace("_", " ")

        val content = try { file.readText() } catch (e: Exception) { "" }
        holder.summary.text = LogAnalyzer(holder.itemView.context.resources).summarize(content)

        holder.view.setOnClickListener { onView(file) }
        holder.delete.setOnClickListener { onDelete(file) }
    }

    override fun getItemCount(): Int = logFiles.size

    fun updateFiles(newFiles: List<File>) {
        logFiles = newFiles
        notifyDataSetChanged()
    }
}