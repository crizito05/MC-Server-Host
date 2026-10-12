package com.pcaudio.minecraft_server.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.pcaudio.minecraft_server.R
import com.pcaudio.minecraft_server.core.ModSearchResult

class ModResultAdapter(
    private var results: List<ModSearchResult>,
    private val isInstalledCheck: (ModSearchResult) -> Boolean,
    private val onOpen: (ModSearchResult) -> Unit
) : RecyclerView.Adapter<ModResultAdapter.ModViewHolder>() {

    class ModViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view
        val icon: ImageView = view.findViewById(R.id.imageModIcon)
        val title: TextView = view.findViewById(R.id.textModTitle)
        val description: TextView = view.findViewById(R.id.textModDescription)
        val downloads: TextView = view.findViewById(R.id.textModDownloads)
        val installedBadge: TextView = view.findViewById(R.id.textInstalledBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_mod_result, parent, false)
        return ModViewHolder(view)
    }

    override fun onBindViewHolder(holder: ModViewHolder, position: Int) {
        val mod = results[position]
        holder.title.text = mod.title
        holder.description.text = mod.description
        holder.downloads.text = "${mod.downloads} download · ${mod.sourceProvider}"
        holder.installedBadge.visibility = if (isInstalledCheck(mod)) View.VISIBLE else View.GONE
        holder.root.setOnClickListener { onOpen(mod) }

        if (mod.iconUrl != null) {
            holder.icon.load(mod.iconUrl) {
                crossfade(true)
                placeholder(android.R.drawable.ic_menu_gallery)
                error(android.R.drawable.ic_menu_gallery)
            }
        } else {
            holder.icon.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    override fun getItemCount(): Int = results.size

    fun updateResults(newResults: List<ModSearchResult>) {
        results = newResults
        notifyDataSetChanged()
    }
}