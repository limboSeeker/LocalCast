package com.localcast.app.rtc

import com.localcast.app.rtc.QualityLadder.MIN_HOLD_MS
import com.localcast.app.rtc.QualityLadder.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityLadderTest {

    private fun labels(w: Int, h: Int, fps: Int, sharp: Boolean) =
        QualityLadder.rungs(w, h, fps, sharp).map { it.label(w, h) }

    @Test
    fun sharpnessFirstDropsFramesBeforePixels() {
        assertEquals(
            listOf("1080p60", "1080p30", "1080p20", "1080p15", "720p15", "720p10"),
            labels(1080, 1728, 60, sharp = true),
        )
        // A 30 fps profile has no separate 30 fps step.
        assertEquals(listOf("1080p30", "1080p20", "1080p15", "720p15", "720p10"), labels(1080, 1728, 30, sharp = true))
        // Already 720p (e.g. the thermal governor lowered the capture): only frame-rate steps.
        assertEquals(listOf("720p60", "720p30", "720p20", "720p15", "720p10"), labels(720, 1152, 60, sharp = true))
    }

    @Test
    fun smoothnessFirstDropsPixelsThenFrames() {
        assertEquals(listOf("720p60", "540p60", "540p30", "540p15"), labels(720, 1152, 60, sharp = false))
        assertEquals(listOf("1080p60", "540p60", "540p30", "540p15"), labels(1080, 1920, 60, sharp = false))
    }

    private val w = 1080
    private val h = 1728
    private val rungs = QualityLadder.rungs(w, h, 60, sharp = true)
    private val cap = 16_000_000

    private fun next(state: State, kbps: Int, now: Long, capBps: Int = cap, bpp: Double = QualityLadder.BITS_PER_PIXEL) =
        QualityLadder.next(rungs, w, h, state, kbps * 1000, capBps, now, bpp)

    @Test
    fun fallsAtOnceWhileTheSizeStays() {
        assertEquals(0, next(State(), 8_000, 0).index) // 1080p60 needs 5.6 Mbit/s
        assertEquals(1, next(State(), 3_000, 0).index) // 1080p30 needs 2.8
        assertEquals(2, next(State(), 2_000, 0).index) // 1080p20 needs 1.87
        assertEquals(3, next(State(), 1_500, 0).index) // 1080p15 needs 1.4
    }

    @Test
    fun aSmallerSizeNeedsASecondPoll() {
        // 0.3 Mbit/s fits only 720p10, but the first poll stays at full size (lowest frame rate).
        val first = next(State(), 300, 0)
        assertEquals(3, first.index)
        assertEquals(5, next(first, 300, 2_000).index)
        // A one-poll dip does not change the size.
        val dip = next(State(index = 3), 300, 0)
        assertEquals(3, dip.index)
        assertEquals(1, dip.shrinkVotes)
        val back = next(dip, 1_500, 2_000)
        assertEquals(3, back.index)
        assertEquals(0, back.shrinkVotes)
    }

    @Test
    fun hevcNeedsFewerBitsPerStep() {
        // 2 Mbit/s: H.264 gets 1080p20, H.265 (0.035 bit/px/frame) 1080p30 (needs 1.96).
        assertEquals(2, next(State(), 2_000, 0).index)
        assertEquals(1, next(State(), 2_000, 0, bpp = QualityLadder.bitsPerPixel("H265")).index)
        assertEquals(QualityLadder.BITS_PER_PIXEL, QualityLadder.bitsPerPixel("H264"), 0.0)
        assertEquals(QualityLadder.BITS_PER_PIXEL, QualityLadder.bitsPerPixel(null), 0.0)
    }

    @Test
    fun aTargetAtTheViewersOwnCapIsNotABadLink() {
        // Manual 4 Mbit/s at 1080p60 (needs 5.6): the target sits at the cap, the link is fine.
        assertEquals(0, next(State(), 3_900, 0, capBps = 4_000_000).index)
        // Well below the cap it is the link.
        assertEquals(3, next(State(), 1_500, 0, capBps = 4_000_000).index)
        // A degraded viewer whose target reaches its cap climbs back.
        val held = State(index = 2, changedAtMs = 0)
        val once = next(held, 3_950, MIN_HOLD_MS, capBps = 4_000_000)
        assertEquals(1, next(once, 3_950, MIN_HOLD_MS + 2_000, capBps = 4_000_000).index)
    }

    private fun lowest(): State = next(next(State(), 300, 0), 300, 1)

    @Test
    fun risesOneStepAfterTheHoldAndTwoGoodPolls() {
        val low = lowest()
        assertEquals(5, low.index)
        // Within the hold nothing rises, however good the target.
        assertEquals(5, next(low, 20_000, 1 + MIN_HOLD_MS - 1).index)
        // After the hold: two polls with 1.25x the budget of 720p15 (0.62 Mbit/s).
        val once = next(low, 800, 1 + MIN_HOLD_MS)
        assertEquals(5, once.index)
        assertEquals(4, next(once, 800, 1 + MIN_HOLD_MS + 2_000).index)
        // A poll without the margin resets the count.
        assertEquals(5, next(once, 700, 1 + MIN_HOLD_MS + 2_000).index)
    }

    @Test
    fun aRiseThatFailsDoublesTheNextHold() {
        val low = lowest()
        val rose = next(next(low, 800, 1 + MIN_HOLD_MS), 800, 1 + MIN_HOLD_MS + 2_000)
        assertEquals(4, rose.index)
        // The link could not carry it: back down within the hold (same size: at once).
        val fell = next(rose, 300, 1 + MIN_HOLD_MS + 4_000)
        assertEquals(5, fell.index)
        assertEquals(2 * MIN_HOLD_MS, fell.holdMs)
        // So the next rise waits twice as long.
        assertEquals(5, next(fell, 800, fell.changedAtMs + MIN_HOLD_MS).index)
    }

    @Test
    fun aFailedRiseToFullQualityAlsoDoublesTheHoldAndASteadyRunForgetsIt() {
        val one = State(index = 1, changedAtMs = 0)
        val voted = next(one, 8_000, MIN_HOLD_MS)
        val top = next(voted, 8_000, MIN_HOLD_MS + 2_000)
        assertEquals(0, top.index)
        // Still full quality a poll later: the "just rose" mark survives.
        val stay = next(top, 8_000, MIN_HOLD_MS + 4_000)
        val fell = next(stay, 3_000, MIN_HOLD_MS + 6_000)
        assertEquals(1, fell.index)
        assertEquals(2 * MIN_HOLD_MS, fell.holdMs)
        // Long steady at full quality resets the hold for the next drop.
        val steady = next(State(index = 0, changedAtMs = 0, holdMs = 4 * MIN_HOLD_MS), 8_000, 10 * MIN_HOLD_MS)
        assertEquals(MIN_HOLD_MS, steady.holdMs)
    }

    @Test
    fun remapKeepsTheQualityWhenTheCaptureChanges() {
        // Thermal: 1080p60 capture becomes 720p60. A viewer on 720p15 stays on 720p15.
        val newRungs = QualityLadder.rungs(720, 1152, 60, sharp = true) // 720p60, 30, 20, 15, 10
        assertEquals(3, QualityLadder.remap(rungs, w, h, 4, newRungs, 720, 1152))
        // A viewer on 1080p30 gets 720p30 (no better than before in size and frame rate).
        assertEquals(1, QualityLadder.remap(rungs, w, h, 1, newRungs, 720, 1152))
        // Full quality stays full quality.
        assertEquals(0, QualityLadder.remap(rungs, w, h, 0, newRungs, 720, 1152))
        assertTrue(newRungs.size == 5)
    }
}
