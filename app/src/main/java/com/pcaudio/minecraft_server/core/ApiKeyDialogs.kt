package com.pcaudio.minecraft_server.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.widget.LinearLayout
import com.pcaudio.minecraft_server.R
import com.pcaudio.minecraft_server.core.ApiKeyStore

object ApiKeyDialogs {

    private const val CURSEFORGE_CONSOLE_URL = "https://console.curseforge.com/"

    fun showCurseForgeMissingKeyDialog(context: Context, onKeySaved: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.tech_cf_missing_title))
            .setMessage(context.getString(R.string.tech_cf_missing_message))
            .setPositiveButton(context.getString(R.string.tech_cf_request_key)) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(CURSEFORGE_CONSOLE_URL))
                context.startActivity(intent)
                showEnterKeyDialog(context, onKeySaved)
            }
            .setNeutralButton(context.getString(R.string.tech_cf_have_key)) { _, _ ->
                showEnterKeyDialog(context, onKeySaved)
            }
            .setNegativeButton(context.getString(R.string.tech_cancel), null)
            .show()
    }

    private fun showEnterKeyDialog(context: Context, onKeySaved: () -> Unit) {
        val input = EditText(context)
        input.hint = context.getString(R.string.tech_cf_hint)

        val container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        container.setPadding(padding, padding, padding, padding)
        container.addView(input)

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.tech_cf_enter_title))
            .setView(container)
            .setPositiveButton(context.getString(R.string.tech_save)) { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotBlank()) {
                    ApiKeyStore(context).setCurseForgeKey(key)
                    onKeySaved()
                }
            }
            .setNegativeButton(context.getString(R.string.tech_cancel), null)
            .show()
    }
}