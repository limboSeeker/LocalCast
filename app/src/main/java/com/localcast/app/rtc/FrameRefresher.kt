package com.localcast.app.rtc

import android.os.SystemClock
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame

/**
 * Sits between the screen capturer and the video source.
 *
 * A VirtualDisplay produces frames only when the screen changes. On a static screen a newly
 * connected viewer would therefore see nothing, and the last encoded frame would never be refined.
 * While idle we re-deliver the last texture with [SurfaceTextureHelper.forceFrame] every
 * [idleRefreshMs] (10 fps by default): a steady input cadence keeps the encoder's rate control and
 * both Wi-Fi radios out of their "idle" states, so the first frames of motion are not delayed.
 * Repeated frames carry the same SurfaceTexture timestamp, which libwebrtc would drop
 * ("same/old timestamp"), so they are re-stamped with the current RTC time here. Output timestamps
 * are at least [MIN_STEP_NS] apart (closer frames are dropped by the encoder pipeline).
 */
class FrameRefresher(
    private val downstream: CapturerObserver,
    private val helper: SurfaceTextureHelper,
    private val idleRefreshMs: Long = DEFAULT_IDLE_REFRESH_MS,
) : CapturerObserver {

    // All fields below are touched only on the SurfaceTextureHelper thread.
    private var lastSourceTimestampNs = Long.MIN_VALUE
    private var lastOutputTimestampNs = Long.MIN_VALUE
    private var lastFrameAtMs = 0L
    private var running = false

    @Volatile var framesDelivered: Long = 0
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (lastFrameAtMs != 0L && SystemClock.elapsedRealtime() - lastFrameAtMs >= idleRefreshMs) {
                helper.forceFrame()
            }
            helper.handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        helper.handler.post {
            running = true
            helper.handler.removeCallbacks(tick)
            helper.handler.postDelayed(tick, TICK_MS)
        }
    }

    fun stop() {
        helper.handler.post {
            running = false
            helper.handler.removeCallbacks(tick)
        }
    }

    /** Push the current picture again (e.g. a viewer just connected). Safe from any thread. */
    fun requestFrame() {
        helper.forceFrame()
    }

    override fun onCapturerStarted(success: Boolean) = downstream.onCapturerStarted(success)

    override fun onCapturerStopped() = downstream.onCapturerStopped()

    override fun onFrameCaptured(frame: VideoFrame) {
        val source = frame.timestampNs
        val isRepeat = source <= lastSourceTimestampNs
        val output = if (isRepeat) {
            maxOf(System.nanoTime(), lastOutputTimestampNs + MIN_STEP_NS) // rtc::TimeNanos() clock
        } else {
            maxOf(source, lastOutputTimestampNs + MIN_STEP_NS)
        }
        if (!isRepeat) lastSourceTimestampNs = source
        lastOutputTimestampNs = output
        lastFrameAtMs = SystemClock.elapsedRealtime()
        framesDelivered++

        if (output == source) {
            downstream.onFrameCaptured(frame)
        } else {
            // Same buffer, new timestamp. The caller still owns (and releases) the original frame;
            // the native side retains the buffer for as long as it needs it.
            downstream.onFrameCaptured(VideoFrame(frame.buffer, frame.rotation, output))
        }
    }

    companion object {
        /** Re-send the last frame when the screen has been static this long (10 fps refresh). */
        const val DEFAULT_IDLE_REFRESH_MS = 100L
        private const val TICK_MS = 20L
        private const val MIN_STEP_NS = 2_000_000L
    }
}
