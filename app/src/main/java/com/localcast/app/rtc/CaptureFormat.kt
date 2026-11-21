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
    private const val AUTO_MIN_BPS = 4_000_000
    private const val AUTO_MAX_BPS = 30_000_000

    /** "Auto" at the reference size: enough for sharp text on slides and documents. */
    private const val AUTO_REF_BPS = 8_000_000.0
    private const val AUTO_REF_PIXELS = 1_150_000.0
    private const val AUTO_60FPS_FACTOR = 1.4

    /**
     * Capture size for a display of [displayWidth]x[displayHeight] in its current orientation.
     * 720p/1080p scale the SHORT side to 720/1080 keeping the aspect ratio (never upscale);
     * NATIVE keeps the display size. Sides are multiples of the encoder's alignment
     * ([Alignment], 2x2 for most encoders; some MediaTek/Exynos encoders need 16).
     */
    fun targetSize(
        displayWidth: Int,
        displayHeight: Int,
        mode: ResolutionMode,
        align: Alignment = Alignment.EVEN,
    ): CaptureSize {
        require(displayWidth > 0 && displayHeight > 0) { "display size must be positive" }
        val shortSide = min(displayWidth, displayHeight)
        val longSide = max(displayWidth, displayHeight)
        val targetShort = when (mode) {
            ResolutionMode.P720 -> 720
            ResolutionMode.P1080 -> 1080
            ResolutionMode.NATIVE -> shortSide
        }.coerceAtMost(shortSide)
        val scale = targetShort.toDouble() / shortSide
        val portrait = displayWidth <= displayHeight
        val shortAlign = if (portrait) align.width else align.height
        val longAlign = if (portrait) align.height else align.width
        val outShort = aligned(targetShort.toDouble(), shortAlign)
        val outLong = aligned(longSide * scale, longAlign)
        return if (portrait) CaptureSize(outShort, outLong) else CaptureSize(outLong, outShort)
    }

    /** Shrinks [size] in ~10 % steps (keeping aspect) until [isSupported] accepts it. */
    fun fitTo(
        size: CaptureSize,
        align: Alignment = Alignment.EVEN,
        isSupported: (Int, Int) -> Boolean,
    ): CaptureSize {
        if (isSupported(size.width, size.height)) return size
        var factor = 1.0
        var candidate = size
        repeat(25) {
            factor *= 0.9
            candidate = CaptureSize(aligned(size.width * factor, align.width), aligned(size.height * factor, align.height))
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
        align: Alignment = Alignment.EVEN,
        isSupported: (Int, Int, Int) -> Boolean,
    ): Pair<CaptureSize, Int> {
        val mode = if (!hardwareEncoder && resolution != ResolutionMode.P720) ResolutionMode.P720 else resolution
        val base = fitTo(targetSize(display.width, display.height, mode, align), align) { w, h -> isSupported(w, h, 30) }
        if (!hardwareEncoder || fpsMode == FpsMode.FPS_30) return base to 30
        if (fpsMode == FpsMode.AUTO && displayRefreshHz < 59f) return base to 30
        if (isSupported(base.width, base.height, 60)) return base to 60
        val forced = fpsMode == FpsMode.FPS_60
        if (!forced && !preferSmooth) return base to 30
        val scaled = fitTo(base, align) { w, h -> isSupported(w, h, 60) }
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
        // Screen content needs bits for edges (text) more than for area: grow sub-linearly with
        // the pixel count, and less than 2× for 60 fps (consecutive frames are similar).
        val area = Math.pow(size.pixels / AUTO_REF_PIXELS, 0.75)
        val rate = if (fps > 30) AUTO_60FPS_FACTOR else 1.0
        return (AUTO_REF_BPS * area * rate).roundToInt().coerceIn(AUTO_MIN_BPS, AUTO_MAX_BPS)
    }

    /** Start bitrate: high enough that the first frames are already sharp on a LAN. */
    fun startBitrateBps(maxBps: Int): Int = (maxBps * 0.6).roundToInt().coerceAtLeast(MIN_BITRATE_BPS)

    /** Required multiples for width and height (from the encoder's VideoCapabilities). */
    data class Alignment(val width: Int, val height: Int) {
        companion object {
            val EVEN = Alignment(2, 2)
        }
    }

    /**
     * Nearest even value for the usual 2-pixel alignment; for coarser alignments round DOWN, so the
     * size never exceeds the captured content (no upscaling, no padding bars).
     */
    private fun aligned(value: Double, multiple: Int): Int {
        val m = max(2, multiple)
        return if (m == 2) max(2, (value / 2.0).roundToInt() * 2) else max(m, (value / m).toInt() * m)
    }
}
