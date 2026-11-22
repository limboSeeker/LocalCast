package com.localcast.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Screen-only preferences (theme, one-time hints). Stream settings live in SettingsRepository. */
object UiPrefs {
    private const val FILE = "ui"
    private const val KEY_THEME = "theme"
    private const val KEY_CAPTURE_HINT = "capture_hint_seen"

    private var prefs: SharedPreferences? = null

    var theme by mutableStateOf(ThemeMode.SYSTEM)
        private set

    /** The "choose Entire screen" hint before the system capture dialog was acknowledged. */
    var captureHintSeen by mutableStateOf(false)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        theme = ThemeMode.entries.firstOrNull { it.name == p.getString(KEY_THEME, null) } ?: ThemeMode.SYSTEM
        captureHintSeen = p.getBoolean(KEY_CAPTURE_HINT, false)
    }

    fun chooseTheme(mode: ThemeMode) {
        theme = mode
        prefs?.edit { putString(KEY_THEME, mode.name) }
    }

    fun markCaptureHintSeen() {
        captureHintSeen = true
        prefs?.edit { putBoolean(KEY_CAPTURE_HINT, true) }
    }
}
