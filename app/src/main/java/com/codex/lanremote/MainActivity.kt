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
            appendLine("辅助功能已连接：${status.optBoolean("accessibilityConnected")}")
            appendLine("网页服务运行中：${status.optBoolean("serverRunning")}")
            appendLine("访问地址：${status.optString("baseUrl")}")
            appendLine("当前包名：${status.optString("lastPackage")}")
            appendLine("当前类名：${status.optString("lastClass")}")
            appendLine("可见控件数：${status.optInt("nodeCount")}")
            appendLine("支持实时画面：${status.optBoolean("screenSupported")}")
            appendLine("最近画面尺寸：${status.optInt("screenWidth")} x ${status.optInt("screenHeight")}")
        }

        binding.instructionsText.text = buildString {
            appendLine("1. 先启用一次辅助功能服务。")
            appendLine("2. 再启动网页服务。")
            appendLine("3. 用另一台设备打开上面的局域网地址。")
            appendLine()
            appendLine("网页功能：")
            appendLine("- 实时画面预览")
            appendLine("- 点击画面直接点屏")
            appendLine("- 返回、主页、最近任务")
            appendLine("- 坐标点击与滑动")
            appendLine("- 文字输入")
            appendLine("- 启动应用")
            appendLine("- 浏览并点击当前控件树")
        }
    }
}
