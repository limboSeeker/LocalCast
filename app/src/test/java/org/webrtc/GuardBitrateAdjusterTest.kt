package org.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardBitrateAdjusterTest {
    private var now = 1_000L

    private fun guard(pinned: Boolean = false) =
        GuardBitrateAdjuster(pinned, /* guardEnabled= */ true, "test") { now }

    /** Feeds [seconds] of frames at [fps] whose output rate is [outBps]. */
    private fun GuardBitrateAdjuster.feed(outBps: Int, fps: Int, seconds: Double) {
        val frameMs = 1000L / fps
        repeat((seconds * fps).toInt()) {
            now += frameMs
            reportEncodedFrame(outBps / 8 / fps)
            adjustedBitrateBps
        }
    }

    @Test
    fun `sustained overshoot cuts the encoder target, recovery restores it`() {
        val g = guard()
        g.setTargets(4_000_000, 30.0)
        assertEquals(4_000_000, g.adjustedBitrateBps)
        g.feed(outBps = 12_000_000, fps = 30, seconds = 0.5) // 3x overshoot
        assertTrue("scale ${g.currentScale()}", g.currentScale() < 0.6)
        assertTrue(g.adjustedBitrateBps < 2_500_000)
        g.feed(outBps = 3_000_000, fps = 30, seconds = 3.0) // back under target
        assertEquals(1.0, g.currentScale(), 1e-9)
        assertEquals(4_000_000, g.adjustedBitrateBps)
    }

    @Test
    fun `one moderate overshoot does not slide down to the floor`() {
        val g = guard()
        g.setTargets(4_000_000, 30.0)
        g.feed(outBps = 7_000_000, fps = 30, seconds = 0.3) // ~1.75x
        assertTrue("scale ${g.currentScale()}", g.currentScale() > GuardBitrateAdjuster.MIN_SCALE + 0.1)
    }

    @Test
    fun `a single key frame is not an overshoot`() {
        val g = guard()
        g.setTargets(2_000_000, 30.0)
        g.feed(outBps = 1_000_000, fps = 30, seconds = 1.0)
        now += 33
        g.reportEncodedFrame(150_000) // big IDR: 4.8 Mbit in one frame
        g.feed(outBps = 1_000_000, fps = 30, seconds = 0.2)
        assertEquals(1.0, g.currentScale(), 1e-9)
    }

    @Test
    fun `exynos pinning halves the codec target at 60 fps without tripping the guard`() {
        val g = guard(pinned = true)
        g.setTargets(8_000_000, 60.0)
        assertEquals(4_000_000, g.adjustedBitrateBps) // libwebrtc FramerateBitrateAdjuster rule
        assertEquals(30.0, g.adjustedFramerateFps, 1e-9)
        g.feed(outBps = 8_500_000, fps = 60, seconds = 2.0) // normal output at the real target
        assertEquals(1.0, g.currentScale(), 1e-9)
    }

    @Test
    fun `never below the floor`() {
        val g = guard()
        g.setTargets(1_000_000, 30.0)
        g.feed(outBps = 20_000_000, fps = 30, seconds = 1.0)
        assertTrue(g.adjustedBitrateBps >= GuardBitrateAdjuster.MIN_GUARD_BPS)
        g.setTargets(300_000, 30.0) // congestion control went lower than the guard floor
        now += 300
        assertEquals(300_000, g.adjustedBitrateBps)
    }
}
