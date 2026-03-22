package com.codex.lanremote

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.codex.lanremote.capture.ScreenCapturePermissionActivity
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

        binding.requestProjectionButton.setOnClickListener {
            startActivity(Intent(this, ScreenCapturePermissionActivity::class.java))
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
            appendLine("辅助功能已连接：${status.optBoolean("accessibilityConnected")}")
            appendLine("网页服务运行中：${status.optBoolean("serverRunning")}")
            appendLine("访问地址：${status.optString("baseUrl")}")
            appendLine("当前包名：${status.optString("lastPackage")}")
            appendLine("当前类名：${status.optString("lastClass")}")
            appendLine("控件数量：${status.optInt("nodeCount")}")
            appendLine("录屏已开启：${status.optBoolean("projectionActive")}")
            appendLine("等待录屏授权：${status.optBoolean("projectionAwaitingApproval")}")
            appendLine("实时画面尺寸：${status.optInt("screenWidth")} x ${status.optInt("screenHeight")}")
        }

        binding.instructionsText.text = buildString {
            appendLine("1. 先启用辅助功能。")
            appendLine("2. 再点一次“申请录屏授权”。")
            appendLine("3. 最后启动网页服务，用别的设备打开上面的局域网地址。")
            appendLine()
            appendLine("安卓 8.1 的实时屏幕必须走系统录屏授权。")
            appendLine("如果屏幕坏了，但辅助功能已经启用，系统授权弹窗有机会自动帮你点掉。")
        }
    }
}
