package com.localcast.app.rtc

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/** What the hardware H.264 encoder of this device can do, as far as the platform tells us. */
data class EncoderCaps(
    val codecName: String,
    /** Required width/height multiples (≥ 2). Some MediaTek/Exynos encoders need 16. */
    val widthAlignment: Int,
    val heightAlignment: Int,
    val bitrateRange: IntRange?,
    val maxInstances: Int?,
    /** Concurrent 1080p30 sessions the performance points guarantee; null = vendor did not say. */
    val sessions1080p30: Int?,
    val vendorLowLatency: Boolean,
    val qpBounds: Boolean,
    val intraRefresh: Boolean,
    val videoCaps: MediaCodecInfo.VideoCapabilities?,
) {
    /** One line testers with other chips can paste: `adb logcat -s LocalCast/Diag | grep caps`. */
    fun logLine(learnedBudget: Int?): String = buildString {
        append("caps ${Build.MANUFACTURER} ${Build.MODEL} (${socName()}) Android ${Build.VERSION.RELEASE}: ")
        append("$codecName align=${widthAlignment}x$heightAlignment ")
        append("bitrate=${bitrateRange?.let { "${it.first / 1000}..${it.last / 1000}kbps" } ?: "?"} ")
        append("instances=${maxInstances ?: "?"} pp1080p30=${sessions1080p30 ?: "?"} ")
        append("learnedBudget=${learnedBudget ?: "-"} ")
        append("vendorLL=$vendorLowLatency qpBounds=$qpBounds intraRefresh=$intraRefresh")
    }
}

private fun socName(): String =
    if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE

object EncoderProbe {
    private const val TAG = "LocalCast/Diag"
    private const val AVC = "video/avc"
    private const val PREFS = "encoder_budget"

    fun probe(codecName: String): EncoderCaps? = runCatching {
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first { it.name == codecName }
        val caps = info.getCapabilitiesForType(AVC)
        val video = caps.videoCapabilities
        EncoderCaps(
            codecName = codecName,
            widthAlignment = video?.widthAlignment?.coerceAtLeast(2) ?: 2,
            heightAlignment = video?.heightAlignment?.coerceAtLeast(2) ?: 2,
            bitrateRange = video?.bitrateRange?.let { it.lower..it.upper },
            maxInstances = caps.maxSupportedInstances.takeIf { it > 0 },
            sessions1080p30 = video?.let { sessionsFor(it, 1080, 1920, 30) },
            vendorLowLatency = vendorParameters(codecName).any { "low-latency" in it || "lowlatency" in it },
            qpBounds = Build.VERSION.SDK_INT >= 31 &&
                caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_QpBounds),
            intraRefresh = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_IntraRefresh),
            videoCaps = video,
        )
    }.onFailure { Log.w(TAG, "encoder probe failed for $codecName", it) }.getOrNull()

    /**
     * How many sessions of [width]x[height]@[fps] the published performance points cover, or null
     * when the vendor publishes none (then nothing is guaranteed and we fall back to a default).
     */
    fun sessionsFor(video: MediaCodecInfo.VideoCapabilities, width: Int, height: Int, fps: Int): Int? {
        val points = video.supportedPerformancePoints?.takeIf { it.isNotEmpty() } ?: return null
        var n = 0
        while (n < 16) {
            val next = MediaCodecInfo.VideoCapabilities.PerformancePoint(width, height, fps * (n + 1))
            if (points.none { it.covers(next) }) break
            n++
        }
        return n
    }

    /** How long a learned limit is trusted (another app may have held an encoder at the time). */
    private const val BUDGET_TTL_MS = 24 * 60 * 60 * 1000L

    /** Failures seen in this process, by codec: a limit is stored only once it repeats. */
    private val pendingFailures = HashMap<String, Int>()

    fun learnedBudget(context: Context, codecName: String?): Int? {
        codecName ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = prefs.getInt(codecName, -1)
        val at = prefs.getLong("$codecName@at", 0L)
        if (value <= 0 || System.currentTimeMillis() - at > BUDGET_TTL_MS) return null
        return value
    }

    /**
     * A hardware init failed while [running] other sessions were up. One failure can be transient
     * (another app holding an encoder, a slow release), so the limit is stored only when it happens
     * again at the same or a lower count, and it expires after a day.
     */
    @Synchronized
    fun learnBudget(context: Context, codecName: String, running: Int) {
        if (running <= 0) return
        val seen = pendingFailures[codecName]
        if (seen == null || running > seen) {
            pendingFailures[codecName] = running
            Log.i(TAG, "hardware encoder refused at $running running sessions (not stored yet)")
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(codecName, running)
            .putLong("$codecName@at", System.currentTimeMillis())
            .apply()
        Log.i(TAG, "learned hardware session budget for $codecName: $running")
    }

    private fun vendorParameters(codecName: String): List<String> {
        if (Build.VERSION.SDK_INT < 31) return emptyList()
        return runCatching {
            val codec = MediaCodec.createByCodecName(codecName)
            try {
                codec.supportedVendorParameters
            } finally {
                codec.release()
            }
        }.getOrDefault(emptyList())
    }
}
