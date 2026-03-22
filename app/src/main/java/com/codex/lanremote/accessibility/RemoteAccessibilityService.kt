package com.codex.lanremote.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.codex.lanremote.control.ControlCenter
import com.codex.lanremote.control.ControlResult
import com.codex.lanremote.control.ScreenFrame
import com.codex.lanremote.control.UiNodeSnapshot
import com.codex.lanremote.server.WebControlService
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        maybeApproveProjectionDialog()
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
            else -> return ControlResult.failure("不支持的全局操作：$name")
        }
        val ok = performGlobalAction(action)
        return if (ok) {
            ControlResult.success("全局操作已发送：$name")
        } else {
            ControlResult.failure("全局操作失败：$name")
        }
    }

    fun tap(x: Int, y: Int): ControlResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return ControlResult.failure("点击手势至少需要安卓 7.0")
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        val ok = dispatchGesture(gesture, null, null)
        return if (ok) {
            ControlResult.success("点击已发送", mapOf("x" to x, "y" to y))
        } else {
            ControlResult.failure("点击发送失败")
        }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ControlResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return ControlResult.failure("滑动手势至少需要安卓 7.0")
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
                "滑动已发送",
                mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "durationMs" to durationMs),
            )
        } else {
            ControlResult.failure("滑动发送失败")
        }
    }

    fun setText(value: String): ControlResult {
        val root = rootInActiveWindow ?: return ControlResult.failure("当前没有活动窗口")
        val target = findEditableNode(root) ?: return ControlResult.failure("没有找到可输入的控件")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) {
            ControlResult.success("文字已写入", mapOf("text" to value))
        } else {
            ControlResult.failure("写入文字失败")
        }
    }

    fun clickNodeByPath(path: String): ControlResult {
        val root = rootInActiveWindow ?: return ControlResult.failure("当前没有活动窗口")
        val node = resolveNodeByPath(root, path) ?: return ControlResult.failure("没有找到控件：$path")
        val clickable = findClickableAncestor(node)
        val clicked = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (clicked) {
            return ControlResult.success("控件点击成功", mapOf("path" to path))
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

    fun captureScreenFrame(): ScreenFrame? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }

        val latch = CountDownLatch(1)
        var frame: ScreenFrame? = null

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    try {
                        val colorSpace = screenshot.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        if (hardwareBitmap != null) {
                            val bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            val output = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, output)
                            frame = ScreenFrame(
                                bytes = output.toByteArray(),
                                mimeType = "image/jpeg",
                                width = bitmap.width,
                                height = bitmap.height,
                                timestampMs = System.currentTimeMillis(),
                            )
                            bitmap.recycle()
                            hardwareBitmap.recycle()
                        }
                    } finally {
                        hardwareBuffer.close()
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            },
        )

        latch.await(2, TimeUnit.SECONDS)
        return frame
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

    private fun maybeApproveProjectionDialog() {
        if (!ControlCenter.isProjectionApprovalRequested()) {
            return
        }

        val root = rootInActiveWindow ?: return
        val packageName = root.packageName?.toString().orEmpty()
        if (!packageName.contains("systemui", ignoreCase = true) && packageName != "android") {
            return
        }

        val node = findNodeByTexts(
            root,
            listOf("立即开始", "开始", "允许", "Start now", "Allow"),
        ) ?: return

        val clickable = findClickableAncestor(node) ?: node
        if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            ControlCenter.clearProjectionApprovalRequest()
        }
    }

    private fun findNodeByTexts(node: AccessibilityNodeInfo, targets: List<String>): AccessibilityNodeInfo? {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        if (targets.any { target -> text.contains(target, ignoreCase = true) || desc.contains(target, ignoreCase = true) }) {
            return node
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val result = findNodeByTexts(child, targets)
            if (result != null) {
                return result
            }
        }
        return null
    }
}
