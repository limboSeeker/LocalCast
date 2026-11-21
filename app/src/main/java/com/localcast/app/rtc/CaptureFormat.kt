package com.localcast.app.rtc

import com.localcast.app.settings.FpsMode
import com.localcast.app.settings.ResolutionMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class CaptureSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
    override fun toString() = "${width}x$height"
}

/** Pure sizing / rate rules (unit-tested). Android capability checks are passed in as lambdas. */
object CaptureFormat {
    const val MIN_BITRATE_BPS = 300_000
    private const val AUTO_MIN_BPS = 3_000_000
    private const val AUTO_MAX_BPS = 20_000_000

    /** Bits per pixel per frame for screen content with H.264 (sharp text at moderate bitrate). */
    private const val AUTO_BPP = 0.08

    /**
     * Capture size for a display of [displayWidth]x[displayHeight] in its current orientation.
     * 720p/1080p scale the SHORT side to 720/1080 keeping the aspect ratio (never upscale);
     * NATIVE keeps the display size. Both sides are even (MediaCodec requires 2x2 alignment).
     */
    fun targetSize(displayWidth: Int, displayHeight: Int, mode: ResolutionMode): CaptureSize {
        require(displayWidth > 0 && displayHeight > 0) { "display size must be positive" }
        val shortSide = min(displayWidth, displayHeight)
        val longSide = max(displayWidth, displayHeight)
        val targetShort = when (mode) {
            ResolutionMode.P720 -> 720
            ResolutionMode.P1080 -> 1080
            ResolutionMode.NATIVE -> shortSide
        }.coerceAtMost(shortSide)
        val scale = targetShort.toDouble() / shortSide
        val outShort = even(targetShort.toDouble())
        val outLong = even(longSide * scale)
        return if (displayWidth <= displayHeight) CaptureSize(outShort, outLong) else CaptureSize(outLong, outShort)
    }

    /** Shrinks [size] in ~10 % steps (keeping aspect) until [isSupported] accepts it. */
    fun fitTo(size: CaptureSize, isSupported: (Int, Int) -> Boolean): CaptureSize {
        if (isSupported(size.width, size.height)) return size
        var factor = 1.0
        var candidate = size
        repeat(25) {
            factor *= 0.9
            candidate = CaptureSize(even(size.width * factor), even(size.height * factor))
            if (isSupported(candidate.width, candidate.height)) return candidate
        }
        return candidate
    }

    /**
     * Picks capture size and frame rate.
     *
     * - Without a hardware encoder (software VP8 fallback) the stream is capped at 720p / 30 fps.
     * - 60 fps is used when requested (or AUTO on a ≥60 Hz display) and the encoder declares it.
     *   If the encoder cannot do 60 fps at the full size, SMOOTH priority / explicit 60 trade a
     *   little resolution for frame rate (down to [MIN_SCALE_FOR_60_AUTO] / [MIN_SCALE_FOR_60_FORCED]
     *   of the short side); SHARP priority keeps the resolution and falls back to 30 fps.
     *
     * [isSupported] = encoder can do (width, height, fps); `{ _, _, _ -> true }` when unknown.
     */
    fun choose(
        display: CaptureSize,
        resolution: ResolutionMode,
        fpsMode: FpsMode,
        preferSmooth: Boolean,
        displayRefreshHz: Float,
        hardwareEncoder: Boolean,
        isSupported: (Int, Int, Int) -> Boolean,
    ): Pair<CaptureSize, Int> {
        val mode = if (!hardwareEncoder && resolution != ResolutionMode.P720) ResolutionMode.P720 else resolution
        val base = fitTo(targetSize(display.width, display.height, mode)) { w, h -> isSupported(w, h, 30) }
        if (!hardwareEncoder || fpsMode == FpsMode.FPS_30) return base to 30
        if (fpsMode == FpsMode.AUTO && displayRefreshHz < 59f) return base to 30
        if (isSupported(base.width, base.height, 60)) return base to 60
        val forced = fpsMode == FpsMode.FPS_60
        if (!forced && !preferSmooth) return base to 30
        val scaled = fitTo(base) { w, h -> isSupported(w, h, 60) }
        val minScale = if (forced) MIN_SCALE_FOR_60_FORCED else MIN_SCALE_FOR_60_AUTO
        val ok = isSupported(scaled.width, scaled.height, 60) &&
            min(scaled.width, scaled.height) >= min(base.width, base.height) * minScale
        return if (ok) scaled to 60 else base to 30
    }

    private const val MIN_SCALE_FOR_60_AUTO = 0.75
    private const val MIN_SCALE_FOR_60_FORCED = 0.5

    /** Max bitrate: user choice in Mbit/s, or automatic from pixel rate when [userMbps] == 0. */
    fun maxBitrateBps(userMbps: Int, size: CaptureSize, fps: Int): Int {
        if (userMbps > 0) return userMbps * 1_000_000
        val auto = size.pixels * fps * AUTO_BPP
        return auto.roundToInt().coerceIn(AUTO_MIN_BPS, AUTO_MAX_BPS)
    }

    /** Start bitrate: high enough that the first frames are already sharp on a LAN. */
    fun startBitrateBps(maxBps: Int): Int = (maxBps * 0.6).roundToInt().coerceAtLeast(MIN_BITRATE_BPS)

    private fun even(value: Double): Int = max(2, (value / 2.0).roundToInt() * 2)
}
