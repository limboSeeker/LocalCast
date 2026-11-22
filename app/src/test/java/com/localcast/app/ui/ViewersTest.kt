package com.localcast.app.ui

import com.localcast.app.server.ViewerStats
import com.localcast.app.service.SenderStats
import com.localcast.app.service.ViewerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class ViewersTest {

    @Test
    fun deviceNames() {
        assertEquals(
            "Mac",
            deviceFromUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/131.0 Safari/537.36"),
        )
        assertEquals("iPad", deviceFromUserAgent("Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Safari/604.1"))
        assertEquals(
            "Pixel 9",
            deviceFromUserAgent("Mozilla/5.0 (Linux; Android 15; Pixel 9 Build/AP3A) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"),
        )
        // Chrome's reduced user agent hides the model behind "K".
        assertEquals("Android", deviceFromUserAgent("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"))
        assertEquals("Smart TV", deviceFromUserAgent("Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 SamsungBrowser/4.0 TV Safari/537.36"))
        assertEquals("Windows", deviceFromUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36 Edg/131.0"))
        assertNull(deviceFromUserAgent(null))
    }

    @Test
    fun megabits() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("2.5", mbps(2_500))
            assertEquals("2", mbps(2_000))
            assertEquals("18", mbps(18_400))
            Locale.setDefault(Locale.forLanguageTag("uk"))
            assertEquals("2,5", mbps(2_500))
        } finally {
            Locale.setDefault(saved)
        }
    }

    private fun viewer(id: String, fps: Double, latency: Double, connected: Boolean = true) = ViewerInfo(
        id = id, remoteAddress = "10.0.0.$id", userAgent = null, connected = connected,
        sender = SenderStats(fps = 60.0), viewer = ViewerStats(fps = fps, latencyMs = latency),
    )

    @Test
    fun healthAndOrder() {
        assertEquals(Health.GOOD, viewerHealth(viewer("1", 59.0, 40.0), 60))
        assertEquals(Health.FAIR, viewerHealth(viewer("2", 40.0, 40.0), 60))
        assertEquals(Health.POOR, viewerHealth(viewer("3", 20.0, 40.0), 60))
        assertEquals(Health.FAIR, viewerHealth(viewer("4", 60.0, 200.0), 60))
        assertEquals(Health.LOST, viewerHealth(viewer("5", 60.0, 40.0, connected = false), 60))

        val sorted = sortWorstFirst(
            listOf(viewer("ok", 60.0, 40.0), viewer("slow", 60.0, 200.0), viewer("poor", 20.0, 50.0)),
            60,
        ).map { it.id }
        assertEquals(listOf("poor", "slow", "ok"), sorted)
    }

    @Test
    fun durations() {
        assertEquals("00:07", formatDuration(7_400))
        assertEquals("12:47", formatDuration((12 * 60 + 47) * 1000L))
        assertEquals("1:02:03", formatDuration((3600 + 2 * 60 + 3) * 1000L))
    }
}
