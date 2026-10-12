package com.pcaudio.minecraft_server.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.widget.LinearLayout
import com.pcaudio.minecraft_server.core.ApiKeyStore

object ApiKeyDialogs {

    private const val CURSEFORGE_CONSOLE_URL = "https://console.curseforge.com/"

    fun showCurseForgeMissingKeyDialog(context: Context, onKeySaved: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Chiave API CurseForge mancante")
            .setMessage("Per cercare mod su CurseForge serve una chiave API gratuita personale. Puoi richiederla in pochi minuti sul portale ufficiale, poi inseriscila qui nell'app.")
            .setPositiveButton("Richiedi chiave") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(CURSEFORGE_CONSOLE_URL))
                context.startActivity(intent)
                showEnterKeyDialog(context, onKeySaved)
            }
            .setNeutralButton("Ho già una chiave") { _, _ ->
                showEnterKeyDialog(context, onKeySaved)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showEnterKeyDialog(context: Context, onKeySaved: () -> Unit) {
        val input = EditText(context)
        input.hint = "Incolla qui la tua API key"

        val container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        container.setPadding(padding, padding, padding, padding)
        container.addView(input)

        AlertDialog.Builder(context)
            .setTitle("Inserisci API key CurseForge")
            .setView(container)
            .setPositiveButton("Salva") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotBlank()) {
                    ApiKeyStore(context).setCurseForgeKey(key)
                    onKeySaved()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
}