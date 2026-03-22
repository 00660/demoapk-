package com.codex.lanremote.control

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.codex.lanremote.accessibility.RemoteAccessibilityService
import com.codex.lanremote.server.MiniWebServer
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
            if (webServer != null) {
                return ControlResult.success("Server already running", mapOf("baseUrl" to baseUrl(context)))
            }
            return try {
                val server = MiniWebServer(context.applicationContext, port)
                server.start(MiniWebServer.SOCKET_READ_TIMEOUT, false)
                webServer = server
                ControlResult.success("Server started", mapOf("baseUrl" to baseUrl(context)))
            } catch (exc: IOException) {
                ControlResult.failure("Failed to start server: ${exc.message}")
            }
        }
    }

    fun stopServer(): ControlResult {
        synchronized(lock) {
            val server = webServer ?: return ControlResult.success("Server already stopped")
            server.stop()
            webServer = null
            return ControlResult.success("Server stopped")
        }
    }

    fun baseUrl(context: Context): String {
        val ip = NetworkUtils.findLanIpv4Address() ?: "127.0.0.1"
        return "http://$ip:$DEFAULT_PORT/"
    }

    fun status(context: Context): JSONObject {
        val nodes = accessibilityRef?.get()?.dumpNodes().orEmpty()
        return JSONObject().apply {
            put("serverRunning", isServerRunning())
            put("accessibilityConnected", isAccessibilityConnected())
            put("baseUrl", baseUrl(context))
            put("lastPackage", lastPackage)
            put("lastClass", lastClass)
            put("lastEvent", lastEvent)
            put("nodeCount", nodes.size)
        }
    }

    fun nodeTreeJson(): JSONObject {
        val service = accessibilityRef?.get()
        if (service == null) {
            return JSONObject()
                .put("success", false)
                .put("message", "Accessibility service is not connected")
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
        }
            .sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }
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
        val service = accessibilityRef?.get() ?: return ControlResult.failure("Accessibility service is not connected")
        return service.performGlobalActionByName(name)
    }

    fun tap(x: Int, y: Int): ControlResult {
        val service = accessibilityRef?.get() ?: return ControlResult.failure("Accessibility service is not connected")
        return service.tap(x, y)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ControlResult {
        val service = accessibilityRef?.get() ?: return ControlResult.failure("Accessibility service is not connected")
        return service.swipe(x1, y1, x2, y2, durationMs)
    }

    fun setText(value: String): ControlResult {
        val service = accessibilityRef?.get() ?: return ControlResult.failure("Accessibility service is not connected")
        return service.setText(value)
    }

    fun clickNode(path: String): ControlResult {
        val service = accessibilityRef?.get() ?: return ControlResult.failure("Accessibility service is not connected")
        return service.clickNodeByPath(path)
    }

    fun launchPackage(context: Context, packageName: String): ControlResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ControlResult.failure("No launcher activity for package: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ControlResult.success("Launch intent sent", mapOf("packageName" to packageName))
        } catch (exc: Exception) {
            ControlResult.failure("Failed to launch package: ${exc.message}")
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
