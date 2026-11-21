package com.localcast.app.service

import android.content.Context
import android.os.Build
import com.localcast.app.BuildConfig

/**
 * Text for "Copy diagnostics": what a tester on another chip (Exynos, MediaTek, …) sends back.
 * The encoder capabilities line is kept after the broadcast stops, so it can be copied later.
 */
object Diagnostics {
    private const val PREFS = "diagnostics"
    private const val KEY_CAPS = "encoder_caps"
    private const val KEY_LOAD = "last_load"

    fun remember(context: Context, encoderCaps: String? = null, load: String? = null) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        encoderCaps?.let { edit.putString(KEY_CAPS, it) }
        load?.let { edit.putString(KEY_LOAD, it) }
        edit.apply()
    }

    fun text(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = CastStateStore.state.value
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        return buildString {
            appendLine("LocalCast ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, soc: $soc, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("caps: ${state.encoderCaps ?: prefs.getString(KEY_CAPS, null) ?: "-"}")
            state.capture?.let { appendLine("capture: ${it.width}x${it.height}@${it.fps} thermal=${it.thermalLevel}") }
            appendLine("load: ${state.load?.summary ?: prefs.getString(KEY_LOAD, null) ?: "-"}")
            state.health?.let { appendLine("health: ${it.level} ${it.issue} total=${it.totalKbps}kbps worstLatency=${it.worstLatencyMs ?: "-"}ms") }
            state.viewers.forEach { v ->
                val s = v.sender
                appendLine(
                    "viewer ${v.id}: ${v.userAgent?.take(60) ?: "-"} connected=${v.connected} " +
                        "codec=${s?.codec ?: "-"} hw=${s?.hardwareEncoder ?: "-"} ${s?.width ?: "-"}x${s?.height ?: "-"} " +
                        "fps=${s?.fps?.toInt() ?: "-"} kbps=${s?.bitrateKbps?.toInt() ?: "-"} ql=${s?.qualityLimitation ?: "-"} " +
                        "latency=${v.viewer?.latencyMs?.toInt() ?: "-"}ms",
                )
            }
        }.trimEnd()
    }
}
