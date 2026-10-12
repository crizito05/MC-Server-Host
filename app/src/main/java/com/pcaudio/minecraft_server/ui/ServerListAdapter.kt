package com.pcaudio.minecraft_server.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pcaudio.minecraft_server.R
import com.pcaudio.minecraft_server.core.ServerConfig
import com.pcaudio.minecraft_server.service.MinecraftServerService

class ServerListAdapter(
    private var servers: List<ServerConfig>,
    private val onOpen: (ServerConfig) -> Unit,
    private val onEdit: (ServerConfig) -> Unit,
    private val onDelete: (ServerConfig) -> Unit
) : RecyclerView.Adapter<ServerListAdapter.ServerViewHolder>() {

    class ServerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.cardRoot)
        val name: TextView = view.findViewById(R.id.textServerName)
        val details: TextView = view.findViewById(R.id.textServerDetails)
        val status: TextView = view.findViewById(R.id.textServerStatus)
        val menu: View = view.findViewById(R.id.buttonMenu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_server, parent, false)
        return ServerViewHolder(view)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        val server = servers[position]
        holder.name.text = server.name

        val loaderLabel = if (server.loaderVersion != null) {
            "${server.loader.name} ${server.loaderVersion}"
        } else {
            server.loader.name
        }
        holder.details.text = "${server.minecraftVersion} · $loaderLabel · ${server.ramMb}MB RAM"

        val isRunning = MinecraftServerService.isRunning(server.id)
        holder.status.text = if (isRunning) "● In esecuzione" else "○ Fermo"

        holder.root.setOnClickListener { onOpen(server) }
        holder.menu.setOnClickListener { anchor ->
            val popup = PopupMenu(anchor.context, anchor)
            popup.menu.add("Modifica")
            popup.menu.add("Elimina")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Modifica" -> onEdit(server)
                    "Elimina" -> onDelete(server)
                }
                true
            }
            popup.show()
        }
    }

    override fun getItemCount(): Int = servers.size

    fun updateServers(newServers: List<ServerConfig>) {
        servers = newServers
        notifyDataSetChanged()
    }
}