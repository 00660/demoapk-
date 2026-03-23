package com.codex.lanremote.control

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.codex.lanremote.accessibility.RemoteAccessibilityService
import com.codex.lanremote.capture.ScreenCaptureManager
import com.codex.lanremote.server.MiniWebServer
import com.codex.lanremote.stream.StreamMode
import com.codex.lanremote.stream.StreamModeStore
import com.codex.lanremote.util.NetworkUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.lang.ref.WeakReference

object ControlCenter {
    const val DEFAULT_PORT = 8080

    private val lock = Any()

    @Volatile
    private var accessibilityRef: WeakReference<RemoteAccessibilityService>? = null

    @Volatile
    private var webServer: MiniWebServer? = null

    @Volatile
    private var lastPackage: String = ""

    @Volatile
    private var lastClass: String = ""

    @Volatile
    private var lastEvent: String = ""

    @Volatile
    private var lastScreenFrame: ScreenFrame? = null

    fun bindAccessibility(service: RemoteAccessibilityService) {
        accessibilityRef = WeakReference(service)
    }

    fun unbindAccessibility(service: RemoteAccessibilityService? = null) {
        val current = accessibilityRef?.get()
        if (service == null || current == service) {
            accessibilityRef = null
        }
    }

    fun updateLastWindow(packageName: String, className: String, eventName: String) {
        lastPackage = packageName
        lastClass = className
        lastEvent = eventName
    }

    fun isAccessibilityConnected(): Boolean = accessibilityRef?.get() != null

    fun isServerRunning(): Boolean = webServer != null

    fun startServer(context: Context, port: Int = DEFAULT_PORT): ControlResult {
        synchronized(lock) {
            ScreenCaptureManager.setMode(context.applicationContext, StreamModeStore.get(context.applicationContext))
            if (webServer != null) {
                return ControlResult.success("网页服务已经在运行", mapOf("baseUrl" to baseUrl(context)))
            }
            return try {
                val server = MiniWebServer(context.applicationContext, port)
                server.start(MiniWebServer.READ_TIMEOUT_MS, false)
                webServer = server
                ControlResult.success("网页服务启动成功", mapOf("baseUrl" to baseUrl(context)))
            } catch (exc: IOException) {
                ControlResult.failure("网页服务启动失败：${exc.message}")
            }
        }
    }

    fun stopServer(): ControlResult {
        synchronized(lock) {
            val server = webServer ?: return ControlResult.success("网页服务已经停止")
            server.stop()
            webServer = null
            return ControlResult.success("网页服务已停止")
        }
    }

    fun baseUrl(context: Context): String {
        val ip = NetworkUtils.findLanIpv4Address() ?: "127.0.0.1"
        return "http://$ip:$DEFAULT_PORT/"
    }

    fun status(context: Context): JSONObject {
        val mode = StreamModeStore.get(context.applicationContext)
        ScreenCaptureManager.setMode(context.applicationContext, mode)
        val nodes = accessibilityRef?.get()?.dumpNodes().orEmpty()
        val frame = lastScreenFrame ?: ScreenCaptureManager.latestFrame()
        return JSONObject().apply {
            put("serverRunning", isServerRunning())
            put("accessibilityConnected", isAccessibilityConnected())
            put("baseUrl", baseUrl(context))
            put("lastPackage", lastPackage)
            put("lastClass", lastClass)
            put("lastEvent", lastEvent)
            put("nodeCount", nodes.size)
            put("screenSupported", true)
            put("screenReady", frame != null)
            put("screenWidth", frame?.width ?: 0)
            put("screenHeight", frame?.height ?: 0)
            put("screenTimestampMs", frame?.timestampMs ?: 0L)
            put("streamMode", mode.wireValue)
            put("projectionActive", ScreenCaptureManager.isProjectionActive())
            put("projectionAwaitingApproval", ScreenCaptureManager.isAwaitingApproval())
            put("browserStreamUrl", baseUrl(context) + "stream.mjpeg")
            put("lowLatencyTcpHost", NetworkUtils.findLanIpv4Address() ?: "127.0.0.1")
            put("lowLatencyTcpPort", ScreenCaptureManager.tcpPort())
            put("lowLatencyClientCount", ScreenCaptureManager.tcpClientCount())
        }
    }

    fun nodeTreeJson(): JSONObject {
        val service = accessibilityRef?.get()
        if (service == null) {
            return JSONObject()
                .put("success", false)
                .put("message", "辅助功能服务尚未连接")
                .put("nodes", JSONArray())
        }

        val nodes = service.dumpNodes()
        val array = JSONArray()
        nodes.forEach { array.put(it.toJson()) }
        return JSONObject()
            .put("success", true)
            .put("count", nodes.size)
            .put("nodes", array)
    }

    fun appsJson(context: Context): JSONObject {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                launchIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL)
        }.sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }

        val array = JSONArray()
        list.forEach { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@forEach
            array.put(
                JSONObject()
                    .put("label", resolveInfo.loadLabel(context.packageManager).toString())
                    .put("packageName", activityInfo.packageName)
                    .put("activityName", activityInfo.name),
            )
        }
        return JSONObject()
            .put("success", true)
            .put("count", array.length())
            .put("apps", array)
    }

    fun performGlobalAction(name: String): ControlResult {
        val service = accessibilityRef?.get() ?: return ControlResult.failure("辅助功能服务尚未连接")
        return service.performGlobalActionByName(name)
    }

    fun tap(x: Int, y: Int): ControlResult {
        val service = accessibilityRef?.get() ?: return ControlResult.failure("辅助功能服务尚未连接")
        return service.tap(x, y)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ControlResult {
        val service = accessibilityRef?.get() ?: return ControlResult.failure("辅助功能服务尚未连接")
        return service.swipe(x1, y1, x2, y2, durationMs)
    }

    fun setText(value: String): ControlResult {
        val service = accessibilityRef?.get() ?: return ControlResult.failure("辅助功能服务尚未连接")
        return service.setText(value)
    }

    fun clickNode(path: String): ControlResult {
        val service = accessibilityRef?.get() ?: return ControlResult.failure("辅助功能服务尚未连接")
        return service.clickNodeByPath(path)
    }

    fun launchPackage(context: Context, packageName: String): ControlResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ControlResult.failure("没有找到可启动的应用：$packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ControlResult.success("应用启动指令已发送", mapOf("packageName" to packageName))
        } catch (exc: Exception) {
            ControlResult.failure("启动应用失败：${exc.message}")
        }
    }

    fun captureScreenFrame(forceRefresh: Boolean = true): ScreenFrame? {
        val projectionFrame = ScreenCaptureManager.latestFrame()
        if (projectionFrame != null) {
            lastScreenFrame = projectionFrame
            return projectionFrame
        }

        val service = accessibilityRef?.get() ?: return lastScreenFrame
        if (!forceRefresh) {
            return lastScreenFrame
        }

        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            service.captureScreenFrame()
        } else {
            null
        } ?: return lastScreenFrame

        lastScreenFrame = frame
        return frame
    }

    fun requestProjectionPermission(context: Context): ControlResult {
        return try {
            ScreenCaptureManager.setMode(context.applicationContext, StreamModeStore.get(context.applicationContext))
            ScreenCaptureManager.requestPermission(context.applicationContext)
            ControlResult.success("录屏授权请求已发起")
        } catch (exc: Exception) {
            ControlResult.failure("发起录屏授权失败：${exc.message}")
        }
    }

    fun clearProjectionApprovalRequest() {
        ScreenCaptureManager.clearAwaitingApproval()
    }

    fun isProjectionApprovalRequested(): Boolean {
        return ScreenCaptureManager.isAwaitingApproval()
    }

    fun currentMode(context: Context): StreamMode {
        return StreamModeStore.get(context.applicationContext)
    }

    fun setStreamMode(context: Context, mode: StreamMode): ControlResult {
        return try {
            StreamModeStore.set(context.applicationContext, mode)
            ScreenCaptureManager.setMode(context.applicationContext, mode)
            ControlResult.success("模式已切换为 ${mode.wireValue}")
        } catch (exc: Exception) {
            ControlResult.failure("切换模式失败：${exc.message}")
        }
    }
}

data class ControlResult(
    val success: Boolean,
    val message: String,
    val payload: Map<String, Any?> = emptyMap(),
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
            .put("success", success)
            .put("message", message)
        payload.forEach { (key, value) -> json.put(key, value) }
        return json
    }

    companion object {
        fun success(message: String, payload: Map<String, Any?> = emptyMap()): ControlResult {
            return ControlResult(true, message, payload)
        }

        fun failure(message: String, payload: Map<String, Any?> = emptyMap()): ControlResult {
            return ControlResult(false, message, payload)
        }
    }
}

data class UiNodeSnapshot(
    val path: String,
    val text: String,
    val contentDescription: String,
    val className: String,
    val packageName: String,
    val clickable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val bounds: String,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("path", path)
            .put("text", text)
            .put("contentDescription", contentDescription)
            .put("className", className)
            .put("packageName", packageName)
            .put("clickable", clickable)
            .put("editable", editable)
            .put("enabled", enabled)
            .put("bounds", bounds)
    }
}

data class ScreenFrame(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val timestampMs: Long,
)
