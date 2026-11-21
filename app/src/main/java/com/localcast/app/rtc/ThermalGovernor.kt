package com.localcast.app.rtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Steps the stream down when the device gets hot, before the OS throttles the SoC (which shows up
 * as stutter and growing latency). Levels are from [PowerManager] thermal status plus the 10 s
 * thermal headroom forecast; stepping back up waits [RECOVERY_MS] at the lower level so the stream
 * does not flap.
 *
 * - [Level.WARM] (MODERATE or headroom ≥ 0.85): 60 → 30 fps.
 * - [Level.HOT] (SEVERE): also 1080p/native → 720p and 70 % bitrate.
 * - [Level.CRITICAL] (CRITICAL and above): also 15 fps.
 */
class ThermalGovernor(context: Context, private val onChange: (Level) -> Unit) {

    enum class Level { NORMAL, WARM, HOT, CRITICAL }

    private val power = context.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile var level: Level = Level.NORMAL
        private set
    private var lowerSinceMs = 0L
    @Volatile private var stopped = false

    private val statusListener = PowerManager.OnThermalStatusChangedListener { evaluate() }
    private val poll = object : Runnable {
        override fun run() {
            if (stopped) return
            evaluate()
            if (!stopped) handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        stopped = false
        handler.post {
            runCatching { power.addThermalStatusListener(statusListener) }
            poll.run()
        }
    }

    /** Safe from any thread: the poll loop checks [stopped] on the main thread. */
    fun stop() {
        stopped = true
        handler.post {
            runCatching { power.removeThermalStatusListener(statusListener) }
            handler.removeCallbacks(poll)
        }
    }

    private fun evaluate() {
        if (stopped) return
        val status = runCatching { power.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        val headroom = runCatching { power.getThermalHeadroom(10) }.getOrDefault(Float.NaN)
        val wanted = levelFor(status, headroom)
        val now = SystemClock.elapsedRealtime()
        val next = when {
            wanted > level -> wanted // heating up: react at once
            wanted < level -> {
                if (lowerSinceMs == 0L) lowerSinceMs = now
                if (now - lowerSinceMs >= RECOVERY_MS) Level.entries[level.ordinal - 1] else level
            }
            else -> level
        }
        if (wanted >= level) lowerSinceMs = 0L
        if (next != level) {
            Log.i(TAG, "thermal $level -> $next (status=$status headroom=$headroom)")
            level = next
            lowerSinceMs = 0L
            onChange(next)
        }
    }

    companion object {
        private const val TAG = "LocalCast/Diag"
        private const val POLL_MS = 10_000L
        private const val RECOVERY_MS = 60_000L

        fun levelFor(status: Int, headroom: Float): Level = when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> Level.CRITICAL
            status >= PowerManager.THERMAL_STATUS_SEVERE -> Level.HOT
            status >= PowerManager.THERMAL_STATUS_MODERATE -> Level.WARM
            !headroom.isNaN() && headroom >= 0.85f -> Level.WARM
            else -> Level.NORMAL
        }
    }
}
