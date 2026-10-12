package com.pcaudio.minecraft_server.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pcaudio.minecraft_server.R
import com.pcaudio.minecraft_server.core.ModLoader
import com.pcaudio.minecraft_server.core.ModVersionFile

class ModVersionAdapter(
    private var versions: List<ModVersionFile>,
    private val currentLoader: ModLoader,
    private val onInstall: (ModVersionFile) -> Unit
) : RecyclerView.Adapter<ModVersionAdapter.VersionViewHolder>() {

    class VersionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val loaderTag: TextView = view.findViewById(R.id.textLoaderTag)
        val versionNumber: TextView = view.findViewById(R.id.textVersionNumber)
        val mcVersions: TextView = view.findViewById(R.id.textVersionMcVersions)
        val install: Button = view.findViewById(R.id.buttonInstallVersion)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VersionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_mod_version, parent, false)
        return VersionViewHolder(view)
    }

    override fun onBindViewHolder(holder: VersionViewHolder, position: Int) {
        val version = versions[position]
        holder.loaderTag.text = currentLoader.name
        holder.versionNumber.text = version.versionNumber
        holder.mcVersions.text = version.gameVersions.joinToString(", ")
        holder.install.setOnClickListener { onInstall(version) }
    }

    override fun getItemCount(): Int = versions.size

    fun updateVersions(newVersions: List<ModVersionFile>) {
        versions = newVersions
        notifyDataSetChanged()
    }
}