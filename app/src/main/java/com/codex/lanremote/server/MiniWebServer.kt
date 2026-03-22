package com.codex.lanremote.server

import android.content.Context
import com.codex.lanremote.control.ControlCenter
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

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
                "/nodes" -> jsonResponse(ControlCenter.nodeTreeJson())
                "/apps" -> jsonResponse(ControlCenter.appsJson(appContext))
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
                        .put("message", "Not found: ${session.uri}"),
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
}
