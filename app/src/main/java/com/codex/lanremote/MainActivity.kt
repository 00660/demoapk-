package com.codex.lanremote

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.codex.lanremote.control.ControlCenter
import com.codex.lanremote.databinding.ActivityMainBinding
import com.codex.lanremote.server.WebControlService

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.openAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.openWifiButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        binding.openBatteryButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        binding.startServerButton.setOnClickListener {
            WebControlService.start(this)
            renderStatus()
        }

        binding.stopServerButton.setOnClickListener {
            WebControlService.stop(this)
            renderStatus()
        }

        binding.openBrowserButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ControlCenter.baseUrl(this))))
        }

        binding.refreshButton.setOnClickListener {
            renderStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun renderStatus() {
        val status = ControlCenter.status(this)
        binding.statusText.text = buildString {
            appendLine("Accessibility connected: ${status.optBoolean("accessibilityConnected")}")
            appendLine("Server running: ${status.optBoolean("serverRunning")}")
            appendLine("Base URL: ${status.optString("baseUrl")}")
            appendLine("Current package: ${status.optString("lastPackage")}")
            appendLine("Current class: ${status.optString("lastClass")}")
            appendLine("Visible nodes: ${status.optInt("nodeCount")}")
        }

        binding.instructionsText.text = buildString {
            appendLine("1. Enable the accessibility service once.")
            appendLine("2. Start the web service.")
            appendLine("3. Open the LAN URL from another device.")
            appendLine()
            appendLine("Web UI actions:")
            appendLine("- Home / Back / Recents")
            appendLine("- Tap and swipe by coordinates")
            appendLine("- Text input")
            appendLine("- Launch installed apps")
            appendLine("- Browse and click current accessibility nodes")
        }
    }
}
