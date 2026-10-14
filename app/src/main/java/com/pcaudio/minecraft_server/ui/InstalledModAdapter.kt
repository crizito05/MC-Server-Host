package com.pcaudio.minecraft_server.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pcaudio.minecraft_server.R
import com.pcaudio.minecraft_server.core.ModUpdateChecker
import com.pcaudio.minecraft_server.core.ModUpdateEntry
import java.io.File

class InstalledModAdapter(
    private var entries: List<ModUpdateEntry>,
    private val onRemove: (File) -> Unit,
    private val onUpdate: (ModUpdateEntry) -> Unit,
    private val onLongPress: (ModUpdateEntry) -> Unit
) : RecyclerView.Adapter<InstalledModAdapter.ModViewHolder>() {

    class ModViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view
        val name: TextView = view.findViewById(R.id.textModFileName)
        val size: TextView = view.findViewById(R.id.textModFileSize)
        val status: TextView = view.findViewById(R.id.textModUpdateStatus)
        val lock: ImageView = view.findViewById(R.id.imageModLocked)
        val update: ImageButton = view.findViewById(R.id.buttonUpdateMod)
        val remove: Button = view.findViewById(R.id.buttonRemoveMod)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_installed_mod, parent, false)
        return ModViewHolder(view)
    }

    override fun onBindViewHolder(holder: ModViewHolder, position: Int) {
        val entry = entries[position]
        val file = entry.file
        val context = holder.itemView.context
        holder.name.text = file.name
        holder.size.text = "${file.length() / 1024} KB · ${entry.sourceProvider ?: context.getString(R.string.installedrow_source_unregistered)}"
        holder.lock.visibility = if (entry.locked) View.VISIBLE else View.GONE
        holder.update.visibility = if (entry.status == ModUpdateChecker.STATUS_UPDATE_AVAILABLE) View.VISIBLE else View.GONE
        holder.status.text = statusText(context, entry)
        holder.update.setOnClickListener { onUpdate(entry) }
        holder.remove.setOnClickListener { onRemove(file) }
        holder.root.setOnLongClickListener {
            onLongPress(entry)
            true
        }
    }

    private fun statusText(context: Context, entry: ModUpdateEntry): String {
        return when (entry.status) {
            ModUpdateChecker.STATUS_UPDATE_AVAILABLE ->
                context.getString(R.string.installedrow_update_available, entry.newest?.versionNumber ?: "")
            ModUpdateChecker.STATUS_UP_TO_DATE -> context.getString(R.string.installedrow_up_to_date)
            ModUpdateChecker.STATUS_BLOCKED ->
                context.getString(R.string.installedrow_blocked, entry.newest?.versionNumber ?: "")
            else -> context.getString(R.string.installedrow_not_checkable)
        }
    }

    override fun getItemCount(): Int = entries.size

    fun updateEntries(newEntries: List<ModUpdateEntry>) {
        entries = newEntries
        notifyDataSetChanged()
    }
}