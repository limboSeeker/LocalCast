package com.localcast.app.service

import com.localcast.app.server.ViewerStats
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthPolicyTest {

    private fun load(
        viewers: Int = 2,
        turnedAway: Int = 0,
        thermal: Int = 0,
        linkKbps: Int = 20_000,
        capKbps: Int = 10_000,
    ) = LoadInfo(viewers, 0, turnedAway, linkKbps, capKbps, viewers, thermal, "")

    private fun viewer(id: String, kbps: Double, latencyMs: Double? = 60.0, limit: String = "none") = ViewerInfo(
        id = id, remoteAddress = "10.0.0.$id", userAgent = null, connected = true,
        sender = SenderStats(bitrateKbps = kbps, qualityLimitation = limit),
        viewer = ViewerStats(latencyMs = latencyMs),
    )

    @Test
    fun allGood() {
        val h = HealthPolicy.evaluate(load(), listOf(viewer("1", 4000.0), viewer("2", 5000.0)))
        assertEquals(HealthIssue.NONE, h.issue)
        assertEquals(HealthLevel.OK, h.level)
        assertEquals(9000, h.totalKbps)
        assertEquals(60, h.worstLatencyMs)
    }

    @Test
    fun priorityOrder() {
        val busy = listOf(viewer("1", 9000.0, latencyMs = 300.0), viewer("2", 9000.0))
        assertEquals(HealthIssue.AT_CAPACITY, HealthPolicy.evaluate(load(turnedAway = 1, thermal = 2), busy).issue)
        assertEquals(HealthIssue.HOT, HealthPolicy.evaluate(load(thermal = 2), busy).issue)
        assertEquals(HealthIssue.NETWORK, HealthPolicy.evaluate(load(), busy).issue)
        assertEquals(HealthIssue.LATENCY, HealthPolicy.evaluate(load(linkKbps = 60_000), busy).issue)
    }

    @Test
    fun criticalHeatIsAProblem() {
        assertEquals(HealthLevel.PROBLEM, HealthPolicy.evaluate(load(thermal = 3), emptyList()).level)
        assertEquals(HealthLevel.WARN, HealthPolicy.evaluate(load(thermal = 2), emptyList()).level)
        // Warm only lowers 60 -> 30 fps: nothing to report.
        assertEquals(HealthIssue.NONE, HealthPolicy.evaluate(load(thermal = 1), emptyList()).issue)
    }

    @Test
    fun starvedViewerMeansNetwork() {
        val h = HealthPolicy.evaluate(load(viewers = 1), listOf(viewer("1", 1200.0, limit = "bandwidth")))
        assertEquals(HealthIssue.NETWORK, h.issue)
        // Limited by bandwidth but close to its cap: congestion control ramping, not a problem.
        val ok = HealthPolicy.evaluate(load(viewers = 1), listOf(viewer("1", 8000.0, limit = "bandwidth")))
        assertEquals(HealthIssue.NONE, ok.issue)
    }

    @Test
    fun singleViewerUsingTheWholeLinkIsFine() {
        val h = HealthPolicy.evaluate(load(viewers = 1), listOf(viewer("1", 19_000.0)))
        assertEquals(HealthIssue.NONE, h.issue)
    }

    @Test
    fun viewersStillConnectingDoNotCount() {
        val connecting = viewer("1", 0.0).copy(connected = false, viewer = ViewerStats(latencyMs = 900.0))
        val h = HealthPolicy.evaluate(load(), listOf(connecting))
        assertEquals(HealthIssue.NONE, h.issue)
        assertEquals(1, h.viewers)
    }

    @Test
    fun viewerBelowFullQualityMeansNetwork() {
        val v = viewer("1", 900.0).let { it.copy(sender = it.sender!!.copy(degraded = true)) }
        assertEquals(HealthIssue.NETWORK, HealthPolicy.evaluate(load(viewers = 1), listOf(v)).issue)
    }
}
