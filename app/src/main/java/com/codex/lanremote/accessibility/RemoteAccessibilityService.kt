package com.codex.lanremote.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.codex.lanremote.control.ControlCenter
import com.codex.lanremote.control.ControlResult
import com.codex.lanremote.control.UiNodeSnapshot
import com.codex.lanremote.server.WebControlService

class RemoteAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        ControlCenter.bindAccessibility(this)
        WebControlService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            return
        }
        ControlCenter.updateLastWindow(
            packageName = event.packageName?.toString().orEmpty(),
            className = event.className?.toString().orEmpty(),
            eventName = AccessibilityEvent.eventTypeToString(event.eventType),
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        ControlCenter.unbindAccessibility(this)
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ControlCenter.unbindAccessibility(this)
        return super.onUnbind(intent)
    }

    fun performGlobalActionByName(name: String): ControlResult {
        val action = when (name.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return ControlResult.failure("Unsupported action: $name")
        }
        val ok = performGlobalAction(action)
        return if (ok) {
            ControlResult.success("Global action sent: $name")
        } else {
            ControlResult.failure("Global action failed: $name")
        }
    }

    fun tap(x: Int, y: Int): ControlResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return ControlResult.failure("Gesture dispatch needs Android 7.0+")
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        val ok = dispatchGesture(gesture, null, null)
        return if (ok) {
            ControlResult.success("Tap dispatched", mapOf("x" to x, "y" to y))
        } else {
            ControlResult.failure("Tap dispatch failed")
        }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ControlResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return ControlResult.failure("Gesture dispatch needs Android 7.0+")
        }
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(50L)))
            .build()
        val ok = dispatchGesture(gesture, null, null)
        return if (ok) {
            ControlResult.success(
                "Swipe dispatched",
                mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "durationMs" to durationMs),
            )
        } else {
            ControlResult.failure("Swipe dispatch failed")
        }
    }

    fun setText(value: String): ControlResult {
        val root = rootInActiveWindow ?: return ControlResult.failure("No active window")
        val target = findEditableNode(root) ?: return ControlResult.failure("No editable node found")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) {
            ControlResult.success("Text set", mapOf("text" to value))
        } else {
            ControlResult.failure("ACTION_SET_TEXT failed")
        }
    }

    fun clickNodeByPath(path: String): ControlResult {
        val root = rootInActiveWindow ?: return ControlResult.failure("No active window")
        val node = resolveNodeByPath(root, path) ?: return ControlResult.failure("Node not found: $path")
        val clickable = findClickableAncestor(node)
        val clicked = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (clicked) {
            return ControlResult.success("Node clicked", mapOf("path" to path))
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return tap(bounds.centerX(), bounds.centerY())
    }

    fun dumpNodes(): List<UiNodeSnapshot> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<UiNodeSnapshot>()
        walkTree(root, "0", out)
        return out
    }

    private fun walkTree(node: AccessibilityNodeInfo, path: String, out: MutableList<UiNodeSnapshot>) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        out += UiNodeSnapshot(
            path = path,
            text = node.text?.toString().orEmpty(),
            contentDescription = node.contentDescription?.toString().orEmpty(),
            className = node.className?.toString().orEmpty(),
            packageName = node.packageName?.toString().orEmpty(),
            clickable = node.isClickable,
            editable = node.isEditable,
            enabled = node.isEnabled,
            bounds = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
        )

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            walkTree(child, "$path.$index", out)
        }
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) {
            return node
        }
        val className = node.className?.toString().orEmpty()
        if (className.contains("EditText", ignoreCase = true)) {
            return node
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val result = findEditableNode(child)
            if (result != null) {
                return result
            }
        }
        return null
    }

    private fun resolveNodeByPath(root: AccessibilityNodeInfo, path: String): AccessibilityNodeInfo? {
        val segments = path.split('.')
        if (segments.isEmpty() || segments.firstOrNull() != "0") {
            return null
        }
        var current: AccessibilityNodeInfo = root
        for (segment in segments.drop(1)) {
            val index = segment.toIntOrNull() ?: return null
            current = current.getChild(index) ?: return null
        }
        return current
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isClickable) {
                return current
            }
            current = current.parent
        }
        return null
    }
}
