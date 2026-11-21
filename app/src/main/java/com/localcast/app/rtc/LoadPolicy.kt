package com.localcast.app.rtc

import kotlin.math.max
import kotlin.math.min

/**
 * How many viewers the device can serve and how they share the Wi-Fi. Pure logic, unit-tested.
 *
 * Every viewer has its own encoder. Hardware H.264 sessions are cheap but limited by the SoC;
 * beyond them a viewer gets software VP8, which costs a CPU core at full size. So:
 * - hardware while the device holds more sessions (unknown limit: try, a refusal is caught);
 * - then a few "lite" VP8 streams (long side ≤ [LITE_LONG_SIDE], ≤ 30 fps, ≤ [LITE_MAX_BPS]);
 * - then the viewer is turned away with a clear reason instead of overloading the device.
 */
object LoadPolicy {

    enum class Tier { HARDWARE, SOFTWARE, BUSY }

    /** Software streams on a device with hardware H.264 are overflow: reduced size and bitrate. */
    const val LITE_LONG_SIDE = 1280
    const val LITE_MAX_BPS = 2_500_000

    /** No viewer of several is squeezed below this by the Wi-Fi split (congestion control may go lower). */
    const val MIN_SHARE_BPS = 1_000_000

    /** Start bitrate for a viewer joining others: its initial probing (×3, ×6) must not starve them. */
    const val JOIN_START_BPS = 1_500_000

    /**
     * Encoder for a new viewer.
     * @param hardwareOffered hardware H.264 is currently offered to viewers.
     * @param hardwareBudget concurrent hardware sessions the device holds, or null while unknown.
     * @param hardwareInUse running hardware encoders plus hardware viewers still connecting.
     * @param softwareInUse viewers on software VP8.
     */
    fun admit(
        hardwareOffered: Boolean,
        hardwareBudget: Int?,
        hardwareInUse: Int,
        softwareInUse: Int,
        softwareBudget: Int,
    ): Tier = when {
        hardwareOffered && (hardwareBudget == null || hardwareInUse < hardwareBudget) -> Tier.HARDWARE
        softwareInUse < softwareBudget -> Tier.SOFTWARE
        else -> Tier.BUSY
    }

    /**
     * Software VP8 sessions the CPU affords next to capture. With hardware H.264 they are lite
     * overflow streams; without it VP8 is everybody's main path, so more are allowed.
     */
    fun softwareBudget(cpuCores: Int, deviceHasHardware: Boolean, heat: ThermalGovernor.Level): Int {
        val cores = max(1, cpuCores)
        if (deviceHasHardware) {
            val base = (cores / 4).coerceIn(1, 3)
            return when {
                heat >= ThermalGovernor.Level.CRITICAL -> 0
                heat >= ThermalGovernor.Level.HOT -> min(base, 1)
                else -> base
            }
        }
        val base = (cores / 2).coerceIn(1, 4)
        return when {
            heat >= ThermalGovernor.Level.HOT -> 1
            else -> base
        }
    }

    /**
     * Video the device's Wi-Fi carries comfortably for all viewers together. A hotspot shares the
     * radio (and usually the channel) with the Wi-Fi connection, so the band of that connection is
     * used; [wifiMhz] null = band unknown: a hotspot alone (apps cannot read its band, and hotspots
     * default to 2.4 GHz on many phones) or Ethernet.
     */
    fun linkBudgetBps(wifiMhz: Int?): Int = when (wifiMhz) {
        null -> 20_000_000
        in 2400..2500 -> 16_000_000
        else -> 60_000_000
    }

    /** Per-viewer bitrate caps: [fullBps] for normal streams, [liteBps] for software overflow ones. */
    data class Shares(val fullBps: Int, val liteBps: Int)

    /**
     * Splits [linkBps] between [fullViewers] and [liteViewers]. A single viewer keeps the whole
     * profile (congestion control alone handles one flow well, and an explicit bitrate setting is
     * respected); with several, nobody's cap exceeds an equal share of the link.
     */
    fun shares(profileMaxBps: Int, linkBps: Int, fullViewers: Int, liteViewers: Int): Shares {
        val liteCap = min(LITE_MAX_BPS, profileMaxBps)
        val viewers = fullViewers + liteViewers
        if (viewers <= 1) return Shares(profileMaxBps, liteCap)
        val equal = linkBps / viewers
        val lite = min(liteCap, max(MIN_SHARE_BPS, equal))
        val full = if (fullViewers > 0) (linkBps - lite * liteViewers) / fullViewers else equal
        return Shares(fullBps = min(profileMaxBps, max(MIN_SHARE_BPS, full)), liteBps = lite)
    }

    /** First viewer starts high (sharp at once); a viewer joining others starts gently. */
    fun startBps(maxBps: Int, othersWatching: Int): Int {
        val start = CaptureFormat.startBitrateBps(maxBps)
        return if (othersWatching == 0) start else min(start, JOIN_START_BPS)
    }

    /** scaleResolutionDownBy for a lite stream of a [width]x[height] capture. */
    fun liteScale(width: Int, height: Int): Double = max(1.0, max(width, height) / LITE_LONG_SIDE.toDouble())

    /** Frame rate of a lite stream. */
    fun liteFps(fps: Int, heat: ThermalGovernor.Level): Int =
        min(fps, if (heat >= ThermalGovernor.Level.WARM) 15 else 30)
}
