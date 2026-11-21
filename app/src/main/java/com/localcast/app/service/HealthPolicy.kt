package com.localcast.app.service

/** Turns load figures and per-viewer stats into the one status line the user sees. Unit-tested. */
object HealthPolicy {

    /** The viewers together use this share of the assumed link capacity: the Wi-Fi is near its limit. */
    const val NETWORK_BUSY_SHARE = 0.85

    /** A viewer limited by bandwidth while getting less than this share of its cap is starved. */
    const val STARVED_SHARE = 0.5

    /** Capture-to-display delay a viewer notices when drawing or pointing. */
    const val HIGH_LATENCY_MS = 150

    fun evaluate(load: LoadInfo, viewers: List<ViewerInfo>): BroadcastHealth {
        val watching = viewers.filter { it.connected }
        val totalKbps = watching.sumOf { it.sender?.bitrateKbps ?: 0.0 }.toInt()
        val worstLatency = watching.mapNotNull { it.viewer?.latencyMs }.maxOrNull()?.toInt()
        val starved = watching.any { v ->
            val s = v.sender ?: return@any false
            s.qualityLimitation == "bandwidth" && (s.bitrateKbps ?: 0.0) < STARVED_SHARE * load.perViewerCapKbps
        }
        val networkBusy = starved || (load.linkKbps > 0 && watching.size > 1 && totalKbps >= NETWORK_BUSY_SHARE * load.linkKbps)
        val issue = when {
            load.turnedAway > 0 -> HealthIssue.AT_CAPACITY
            load.thermalLevel >= 2 -> HealthIssue.HOT
            networkBusy -> HealthIssue.NETWORK
            worstLatency != null && worstLatency > HIGH_LATENCY_MS -> HealthIssue.LATENCY
            else -> HealthIssue.NONE
        }
        val level = when {
            issue == HealthIssue.NONE -> HealthLevel.OK
            issue == HealthIssue.HOT && load.thermalLevel >= 3 -> HealthLevel.PROBLEM
            else -> HealthLevel.WARN
        }
        return BroadcastHealth(
            level = level,
            issue = issue,
            viewers = viewers.size,
            liteViewers = load.liteViewers,
            turnedAway = load.turnedAway,
            totalKbps = totalKbps,
            linkKbps = load.linkKbps,
            worstLatencyMs = worstLatency,
            thermalLevel = load.thermalLevel,
            hardwareEncoders = load.hardwareEncoders,
        )
    }
}
