package com.codex.lanremote

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.codex.lanremote.control.ControlCenter
import com.codex.lanremote.databinding.ActivityMainBinding
import com.codex.lanremote.server.WebControlService
import com.codex.lanremote.stream.StreamMode

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.openAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.requestProjectionButton.setOnClickListener {
            ControlCenter.requestProjectionPermission(this)
            renderStatus()
        }

        binding.openWifiButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        binding.openBatteryButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        binding.browserModeButton.setOnClickListener {
            ControlCenter.setStreamMode(this, StreamMode.BROWSER_MJPEG)
            renderStatus()
        }

        binding.lowLatencyModeButton.setOnClickListener {
            ControlCenter.setStreamMode(this, StreamMode.LOW_LATENCY_H264)
            renderStatus()
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
            appendLine("辅助功能已连接：${status.optBoolean("accessibilityConnected")}")
            appendLine("网页服务运行中：${status.optBoolean("serverRunning")}")
            appendLine("访问地址：${status.optString("baseUrl")}")
            appendLine("当前包名：${status.optString("lastPackage")}")
            appendLine("当前类名：${status.optString("lastClass")}")
            appendLine("控件数量：${status.optInt("nodeCount")}")
            appendLine("录屏已开启：${status.optBoolean("projectionActive")}")
            appendLine("等待录屏授权：${status.optBoolean("projectionAwaitingApproval")}")
            appendLine("实时画面尺寸：${status.optInt("screenWidth")} x ${status.optInt("screenHeight")}")
            appendLine("当前模式：${status.optString("streamMode")}")
            appendLine("浏览器流：${status.optString("browserStreamUrl")}")
            appendLine("低延迟 TCP：${status.optString("lowLatencyTcpHost")}:${status.optInt("lowLatencyTcpPort")}")
            appendLine("低延迟客户端数：${status.optInt("lowLatencyClientCount")}")
        }

        binding.instructionsText.text = buildString {
            appendLine("1. 先启用辅助功能。")
            appendLine("2. 选择一种流模式。")
            appendLine("3. 再点一次“申请录屏授权”。")
            appendLine("4. 最后启动网页服务或低延迟客户端。")
            appendLine()
            appendLine("浏览器模式：网页里直接看实时画面。")
            appendLine("低延迟模式：输出 H.264 TCP 流，更适合自定义客户端。")
        }
    }
}
