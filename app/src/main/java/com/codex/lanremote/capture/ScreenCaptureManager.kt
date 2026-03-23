package com.codex.lanremote.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.DisplayMetrics
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import com.codex.lanremote.control.ScreenFrame
import java.io.ByteArrayOutputStream

object ScreenCaptureManager {
    private val lock = Any()

    @Volatile
    private var mediaProjection: MediaProjection? = null

    @Volatile
    private var imageReader: ImageReader? = null

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var workerThread: HandlerThread? = null

    @Volatile
    private var workerHandler: Handler? = null

    @Volatile
    private var lastFrame: ScreenFrame? = null

    @Volatile
    private var awaitingApproval: Boolean = false

    fun markAwaitingApproval() {
        awaitingApproval = true
    }

    fun clearAwaitingApproval() {
        awaitingApproval = false
    }

    fun isAwaitingApproval(): Boolean = awaitingApproval

    fun isProjectionActive(): Boolean = mediaProjection != null && virtualDisplay != null

    fun latestFrame(): ScreenFrame? = lastFrame

    fun requestPermission(context: Context) {
        markAwaitingApproval()
        val intent = Intent(context, ScreenCapturePermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        context.startActivity(intent)
    }

    fun startProjection(context: Context, resultCode: Int, data: Intent) {
        synchronized(lock) {
            stopProjection()

            val realMetrics = DisplayMetrics()
            val appMetrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(realMetrics)
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(appMetrics)

            val width = realMetrics.widthPixels.coerceAtLeast(1)
            val realHeight = realMetrics.heightPixels.coerceAtLeast(1)
            val appHeight = appMetrics.heightPixels.coerceAtLeast(1)
            val density = realMetrics.densityDpi.coerceAtLeast(1)

            val navigationBarHeight = resolveNavigationBarHeight(context)
            val bottomGap = (realHeight - appHeight).coerceAtLeast(0)
            val extraBottom = maxOf(navigationBarHeight, bottomGap)
            val height = (realHeight + extraBottom).coerceAtLeast(realHeight)

            val thread = HandlerThread("screen-capture-worker").also { it.start() }
            val handler = Handler(thread.looper)
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val fullBitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888,
                    )
                    fullBitmap.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, width, height)
                    val output = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 65, output)
                    lastFrame = ScreenFrame(
                        bytes = output.toByteArray(),
                        mimeType = "image/jpeg",
                        width = width,
                        height = height,
                        timestampMs = System.currentTimeMillis(),
                    )
                    cropped.recycle()
                    fullBitmap.recycle()
                } finally {
                    image.close()
                }
            }, handler)

            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, data)
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopProjection()
                    }
                },
                handler,
            )

            val display = projection.createVirtualDisplay(
                "lan-remote-screen",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )

            workerThread = thread
            workerHandler = handler
            imageReader = reader
            mediaProjection = projection
            virtualDisplay = display
            awaitingApproval = false
        }
    }

    private fun resolveNavigationBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId == 0) {
            return 0
        }
        return runCatching { context.resources.getDimensionPixelSize(resourceId) }.getOrDefault(0)
    }

    fun stopProjection() {
        synchronized(lock) {
            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null

            val projection = mediaProjection
            mediaProjection = null
            projection?.stop()

            workerThread?.quitSafely()
            workerThread = null
            workerHandler = null
        }
    }
}
