package com.pcaudio.minecraft_server.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pcaudio.minecraft_server.R

class NetworkHelpActivity : AppCompatActivity() {

    private val sections = listOf(
        R.string.network_help_how_heading to R.string.network_help_how_body,
        R.string.network_help_phone_heading to R.string.network_help_phone_body,
        R.string.network_help_pc_heading to R.string.network_help_pc_body,
        R.string.network_help_code_heading to R.string.network_help_code_body,
        R.string.network_help_both_heading to R.string.network_help_both_body
    )

    private val troubles = listOf(
        R.string.network_help_trouble_api,
        R.string.network_help_trouble_address,
        R.string.network_help_trouble_activation,
        R.string.network_help_trouble_existing_agent,
        R.string.network_help_trouble_tunnel_lifetime
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_help)
        title = getString(R.string.network_help_title)

        val container = findViewById<LinearLayout>(R.id.containerHelpSteps)
        for ((headingRes, bodyRes) in sections) {
            container.addView(buildHeading(getString(headingRes)))
            container.addView(buildBody(getString(bodyRes)))
        }
        container.addView(buildHeading(getString(R.string.network_help_troubles_heading)))
        for (troubleRes in troubles) {
            container.addView(buildBody(getString(troubleRes)))
        }

        findViewById<Button>(R.id.buttonCloseHelp).setOnClickListener { finish() }
    }

    private fun buildHeading(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }
    }

    private fun buildBody(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        }
    }
}