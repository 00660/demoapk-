package com.codex.lanremote.server

import android.content.Context
import com.codex.lanremote.control.ControlCenter
import com.codex.lanremote.stream.StreamMode
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class MiniWebServer(
    private val appContext: Context,
    port: Int,
) : NanoHTTPD("0.0.0.0", port) {

    companion object {
        const val READ_TIMEOUT_MS: Int = 5_000
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (session.method == Method.POST) {
                session.parseBody(mutableMapOf())
            }
            val params = flatten(session.parameters)
            when (session.uri) {
                "/" -> htmlResponse(WebUiRenderer.render(appContext))
                "/status" -> jsonResponse(ControlCenter.status(appContext))
                "/mode/get" -> jsonResponse(JSONObject().put("mode", ControlCenter.currentMode(appContext).wireValue))
                "/mode/set" -> jsonResponse(
                    ControlCenter.setStreamMode(
                        appContext,
                        StreamMode.fromWireValue(params["value"]),
                    ).toJson(),
                )
                "/nodes" -> jsonResponse(ControlCenter.nodeTreeJson())
                "/apps" -> jsonResponse(ControlCenter.appsJson(appContext))
                "/screen.jpg" -> imageResponse()
                "/stream.mjpeg" -> mjpegResponse()
                "/projection/request" -> jsonResponse(ControlCenter.requestProjectionPermission(appContext).toJson())
                "/action" -> jsonResponse(ControlCenter.performGlobalAction(params["name"].orEmpty()).toJson())
                "/gesture/tap" -> jsonResponse(
                    ControlCenter.tap(
                        x = params["x"]?.toIntOrNull() ?: 0,
                        y = params["y"]?.toIntOrNull() ?: 0,
                    ).toJson(),
                )
                "/gesture/swipe" -> jsonResponse(
                    ControlCenter.swipe(
                        x1 = params["x1"]?.toIntOrNull() ?: 0,
                        y1 = params["y1"]?.toIntOrNull() ?: 0,
                        x2 = params["x2"]?.toIntOrNull() ?: 0,
                        y2 = params["y2"]?.toIntOrNull() ?: 0,
                        durationMs = params["duration"]?.toLongOrNull() ?: 300L,
                    ).toJson(),
                )
                "/text" -> jsonResponse(ControlCenter.setText(params["value"].orEmpty()).toJson())
                "/node/click" -> jsonResponse(ControlCenter.clickNode(params["path"].orEmpty()).toJson())
                "/launch" -> jsonResponse(ControlCenter.launchPackage(appContext, params["package"].orEmpty()).toJson())
                else -> jsonResponse(
                    JSONObject()
                        .put("success", false)
                        .put("message", "没有这个接口：${session.uri}"),
                    status = Response.Status.NOT_FOUND,
                )
            }
        } catch (exc: Exception) {
            jsonResponse(
                JSONObject()
                    .put("success", false)
                    .put("message", exc.message ?: exc::class.java.simpleName),
                status = Response.Status.INTERNAL_ERROR,
            )
        }
    }

    private fun flatten(raw: Map<String, List<String>>): Map<String, String> {
        return raw.mapValues { (_, value) -> value.firstOrNull().orEmpty() }
    }

    private fun jsonResponse(
        json: JSONObject,
        status: Response.Status = Response.Status.OK,
    ): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json.toString()).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun htmlResponse(html: String): Response {
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun imageResponse(): Response {
        val frame = ControlCenter.captureScreenFrame(forceRefresh = true)
            ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "text/plain; charset=utf-8",
                "当前还没有屏幕画面，请先发起录屏授权。",
            )

        return newFixedLengthResponse(
            Response.Status.OK,
            frame.mimeType,
            ByteArrayInputStream(frame.bytes),
            frame.bytes.size.toLong(),
        ).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun mjpegResponse(): Response {
        val input = PipedInputStream(1024 * 1024)
        val output = PipedOutputStream(input)

        Thread {
            var lastTimestamp = 0L
            try {
                while (true) {
                    val frame = ControlCenter.captureScreenFrame(forceRefresh = false)
                    if (frame != null && frame.timestampMs != lastTimestamp) {
                        lastTimestamp = frame.timestampMs
                        val header = buildString {
                            append("--frame\r\n")
                            append("Content-Type: ${frame.mimeType}\r\n")
                            append("Content-Length: ${frame.bytes.size}\r\n")
                            append("\r\n")
                        }.toByteArray(Charsets.UTF_8)
                        output.write(header)
                        output.write(frame.bytes)
                        output.write("\r\n".toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                    Thread.sleep(30)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { output.close() }
            }
        }.start()

        return newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=frame",
            input,
        ).apply {
            addHeader("Cache-Control", "no-store")
        }
    }
}
