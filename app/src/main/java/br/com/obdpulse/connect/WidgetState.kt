package br.com.obdpulse.connect

import android.content.Context

object WidgetState {

    const val BUSY_TIMEOUT_MS = 120_000L

    private const val FILE = "widget"
    private const val KEY_TEXT = "status_text"
    private const val KEY_BUSY = "busy"
    private const val KEY_ERROR = "error"
    private const val KEY_AT = "at"

    data class Status(val text: String, val busy: Boolean, val error: Boolean, val atMs: Long)

    fun set(context: Context, text: String, busy: Boolean, error: Boolean = false, now: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putString(KEY_TEXT, text)
            .putBoolean(KEY_BUSY, busy)
            .putBoolean(KEY_ERROR, error)
            .putLong(KEY_AT, now)
            .apply()
        VehicleWidgetProvider.refresh(context)
    }

    fun get(context: Context, now: Long = System.currentTimeMillis()): Status? {
        val prefs = prefs(context)
        val text = prefs.getString(KEY_TEXT, null) ?: return null
        val at = prefs.getLong(KEY_AT, 0L)
        val busy = prefs.getBoolean(KEY_BUSY, false) && now - at < BUSY_TIMEOUT_MS
        return Status(text, busy, prefs.getBoolean(KEY_ERROR, false), at)
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
