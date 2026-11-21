package com.localcast.app.ui

import androidx.annotation.StringRes
import com.localcast.app.R
import com.localcast.app.settings.FpsMode
import com.localcast.app.settings.Priority
import com.localcast.app.settings.ResolutionMode
import com.localcast.app.settings.StreamSettings

/** One-tap quality modes. Every preset leaves the bitrate on "Авто". */
enum class Preset(
    val resolution: ResolutionMode,
    val fps: FpsMode,
    val priority: Priority,
    @param:StringRes val title: Int,
    @param:StringRes val spec: Int,
    @param:StringRes val hint: Int,
) {
    SPEED(
        ResolutionMode.P720, FpsMode.FPS_60, Priority.SMOOTH,
        R.string.ui_preset_speed, R.string.ui_preset_speed_spec, R.string.ui_preset_speed_hint,
    ),
    BALANCE(
        ResolutionMode.P1080, FpsMode.AUTO, Priority.SMOOTH,
        R.string.ui_preset_balance, R.string.ui_preset_balance_spec, R.string.ui_preset_balance_hint,
    ),
    QUALITY(
        ResolutionMode.NATIVE, FpsMode.FPS_30, Priority.SHARP,
        R.string.ui_preset_quality, R.string.ui_preset_quality_spec, R.string.ui_preset_quality_hint,
    ),
    ;

    fun applyTo(s: StreamSettings): StreamSettings =
        s.copy(resolution = resolution, fps = fps, priority = priority, bitrateMbps = 0)

    fun matches(s: StreamSettings): Boolean =
        s.resolution == resolution && s.fps == fps && s.priority == priority && s.bitrateMbps == 0

    companion object {
        /** null = "Свій": the user tuned something in "Розширені". */
        fun of(s: StreamSettings): Preset? = entries.firstOrNull { it.matches(s) }
    }
}
