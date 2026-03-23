package com.codex.lanremote.stream

enum class StreamMode(val wireValue: String) {
    BROWSER_MJPEG("browser_mjpeg"),
    LOW_LATENCY_H264("low_latency_h264");

    companion object {
        fun fromWireValue(value: String?): StreamMode {
            return entries.firstOrNull { it.wireValue == value } ?: BROWSER_MJPEG
        }
    }
}
