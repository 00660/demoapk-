package com.codex.lanremote.stream

import android.content.Context

object StreamModeStore {
    private const val PREFS_NAME = "lan_remote_mode"
    private const val KEY_MODE = "stream_mode"

    fun get(context: Context): StreamMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return StreamMode.fromWireValue(prefs.getString(KEY_MODE, StreamMode.BROWSER_MJPEG.wireValue))
    }

    fun set(context: Context, mode: StreamMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MODE, mode.wireValue).apply()
    }
}
