package com.codex.lanremote.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import com.codex.lanremote.control.ScreenFrame
import com.codex.lanremote.stream.H264TcpServer
import com.codex.lanremote.stream.StreamMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.concurrent.thread

object ScreenCaptureManager {
    const val LOW_LATENCY_TCP_PORT: Int = 27183

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

    @Volatile
    private var currentMode: StreamMode = StreamMode.LOW_LATENCY_H264

    @Volatile
    private var h264Server: H264TcpServer? = null

    @Volatile
    private var encoder: MediaCodec? = null

    @Volatile
    private var encoderSurface: Surface? = null

    @Volatile
    private var encoderDrainThread: Thread? = null

    @Volatile
    private var encoderRunning: Boolean = false

    @Volatile
    private var codecConfigBytes: ByteArray? = null

    fun markAwaitingApproval() {
        awaitingApproval = true
    }

    fun clearAwaitingApproval() {
        awaitingApproval = false
    }

    fun isAwaitingApproval(): Boolean = awaitingApproval

    fun isProjectionActive(): Boolean = mediaProjection != null && virtualDisplay != null

    fun latestFrame(): ScreenFrame? = lastFrame

    fun tcpPort(): Int = LOW_LATENCY_TCP_PORT

    fun tcpClientCount(): Int = h264Server?.clientCount() ?: 0

    fun mode(): StreamMode = currentMode

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

    fun setMode(context: Context, mode: StreamMode) {
        synchronized(lock) {
            if (currentMode == mode) {
                return
            }
            currentMode = mode
            if (mediaProjection != null) {
                configurePipelineLocked(context.applicationContext)
            }
        }
    }

    fun startProjection(context: Context, resultCode: Int, data: Intent) {
        synchronized(lock) {
            stopProjection()

            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, data)
            mediaProjection = projection
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopProjection()
                    }
                },
                null,
            )

            configurePipelineLocked(context.applicationContext)
            awaitingApproval = false
        }
    }

    private fun configurePipelineLocked(context: Context) {
        releasePipelineLocked()

        val metrics = projectionMetrics(context)
        when (currentMode) {
            StreamMode.BROWSER_MJPEG -> startBrowserPipelineLocked(context, metrics)
            StreamMode.LOW_LATENCY_H264 -> startLowLatencyPipelineLocked(context, metrics)
        }
    }

    private fun startBrowserPipelineLocked(context: Context, metrics: ProjectionMetrics) {
        val thread = HandlerThread("screen-capture-worker").also { it.start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(metrics.width, metrics.height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * metrics.width

                val fullBitmap = Bitmap.createBitmap(
                    metrics.width + rowPadding / pixelStride,
                    metrics.height,
                    Bitmap.Config.ARGB_8888,
                )
                fullBitmap.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, metrics.width, metrics.height)
                val output = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, 72, output)
                lastFrame = ScreenFrame(
                    bytes = output.toByteArray(),
                    mimeType = "image/jpeg",
                    width = metrics.width,
                    height = metrics.height,
                    timestampMs = System.currentTimeMillis(),
                )
                cropped.recycle()
                fullBitmap.recycle()
            } finally {
                image.close()
            }
        }, handler)

        val display = mediaProjection!!.createVirtualDisplay(
            "lan-remote-browser",
            metrics.width,
            metrics.height,
            metrics.density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )

        workerThread = thread
        workerHandler = handler
        imageReader = reader
        virtualDisplay = display
    }

    private fun startLowLatencyPipelineLocked(context: Context, metrics: ProjectionMetrics) {
        lastFrame = null
        val server = H264TcpServer(LOW_LATENCY_TCP_PORT)
        server.start()
        h264Server = server

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, metrics.width, metrics.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (metrics.width * metrics.height * 4).coerceAtLeast(2_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        val display = mediaProjection!!.createVirtualDisplay(
            "lan-remote-h264",
            metrics.width,
            metrics.height,
            metrics.density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        )

        codecConfigBytes = null
        encoder = codec
        encoderSurface = surface
        virtualDisplay = display
        encoderRunning = true
        encoderDrainThread = thread(name = "h264-drain", start = true) {
            drainEncoder(codec, server)
        }
    }

    private fun drainEncoder(codec: MediaCodec, server: H264TcpServer) {
        val info = MediaCodec.BufferInfo()
        while (encoderRunning) {
            val index = codec.dequeueOutputBuffer(info, 10_000)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    codecConfigBytes = extractCodecConfig(codec.outputFormat)
                }

                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val payload = ByteArray(info.size)
                        buffer.get(payload)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                            codecConfigBytes?.let(server::publish)
                        }
                        server.publish(toAnnexB(payload))
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun extractCodecConfig(format: MediaFormat): ByteArray? {
        val out = ByteArrayOutputStream()
        format.getByteBuffer("csd-0")?.let { out.write(normalizeNalBuffer(it)) }
        format.getByteBuffer("csd-1")?.let { out.write(normalizeNalBuffer(it)) }
        val bytes = out.toByteArray()
        return bytes.takeIf { it.isNotEmpty() }
    }

    private fun normalizeNalBuffer(buffer: ByteBuffer): ByteArray {
        val dup = buffer.duplicate()
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        return toAnnexB(bytes)
    }

    private fun toAnnexB(bytes: ByteArray): ByteArray {
        if (bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte() && bytes[3] == 1.toByte()) {
            return bytes
        }
        if (bytes.size >= 3 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte()) {
            return bytes
        }
        if (bytes.size < 4) {
            return byteArrayOf(0, 0, 0, 1) + bytes
        }

        return runCatching {
            val output = ByteArrayOutputStream()
            var offset = 0
            while (offset + 4 <= bytes.size) {
                val length = (
                    ((bytes[offset].toInt() and 0xFF) shl 24) or
                        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                        (bytes[offset + 3].toInt() and 0xFF)
                    )
                offset += 4
                if (length <= 0 || offset + length > bytes.size) {
                    throw IllegalStateException("bad avcc packet")
                }
                output.write(byteArrayOf(0, 0, 0, 1))
                output.write(bytes, offset, length)
                offset += length
            }
            output.toByteArray()
        }.getOrElse {
            byteArrayOf(0, 0, 0, 1) + bytes
        }
    }

    private fun projectionMetrics(context: Context): ProjectionMetrics {
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
        return ProjectionMetrics(width, height, density)
    }

    private fun resolveNavigationBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId == 0) {
            return 0
        }
        return runCatching { context.resources.getDimensionPixelSize(resourceId) }.getOrDefault(0)
    }

    private fun releasePipelineLocked() {
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null

        encoderRunning = false
        encoderDrainThread?.join(200)
        encoderDrainThread = null

        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null

        try {
            encoderSurface?.release()
        } catch (_: Exception) {
        }
        encoderSurface = null

        h264Server?.stop()
        h264Server = null
        codecConfigBytes = null
    }

    fun stopProjection() {
        synchronized(lock) {
            releasePipelineLocked()

            val projection = mediaProjection
            mediaProjection = null
            try {
                projection?.stop()
            } catch (_: Exception) {
            }
        }
    }

    private data class ProjectionMetrics(
        val width: Int,
        val height: Int,
        val density: Int,
    )
}
