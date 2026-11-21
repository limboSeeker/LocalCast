package com.localcast.app.rtc

import android.util.Log
import com.localcast.app.BuildConfig

/**
 * Debug-build-only overrides for A/B measurements, set over adb without rebuilding, e.g.
 * `adb shell setprop debug.localcast.idle_ms 500` (frame re-send interval on a static screen) or
 * `adb shell setprop debug.localcast.guard 0` (disable the encoder overshoot guard).
 * Read when a broadcast starts. Release builds always use the defaults.
 */
internal object DebugKnobs {
    fun int(name: String, default: Int): Int {
        if (!BuildConfig.DEBUG) return default
        val value = runCatching {
            val props = Class.forName("android.os.SystemProperties")
            props.getMethod("get", String::class.java, String::class.java)
                .invoke(null, "debug.localcast.$name", "") as String
        }.getOrNull()?.toIntOrNull() ?: return default
        Log.i("LocalCast/Debug", "knob $name=$value (default $default)")
        return value
    }
}
