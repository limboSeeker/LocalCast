package com.localcast.app.rtc

import com.localcast.app.rtc.QualityLadder.MIN_HOLD_MS
import com.localcast.app.rtc.QualityLadder.State
import org.junit.Assert.assertEquals
import org.junit.Test

class QualityLadderTest {

    private fun labels(w: Int, h: Int, fps: Int, sharp: Boolean) =
        QualityLadder.rungs(w, h, fps, sharp).map { it.label(w, h) }

    @Test
    fun sharpnessFirstDropsFramesThenPixels() {
        assertEquals(listOf("1080p60", "1080p30", "720p30", "720p15", "720p10"), labels(1080, 1728, 60, sharp = true))
        // A 30 fps profile has no separate 30 fps step.
        assertEquals(listOf("1080p30", "720p30", "720p15", "720p10"), labels(1080, 1728, 30, sharp = true))
        // Already 720p (e.g. the thermal governor lowered the capture): only frame-rate steps.
        assertEquals(listOf("720p60", "720p30", "720p15", "720p10"), labels(720, 1152, 60, sharp = true))
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

    private fun next(state: State, kbps: Int, now: Long, capBps: Int = cap) =
        QualityLadder.next(rungs, w, h, state, kbps * 1000, capBps, now)

    @Test
    fun fallsStraightToTheStepThatFits() {
        assertEquals(0, next(State(), 8_000, 0).index) // 1080p60 needs 5.6 Mbit/s
        assertEquals(1, next(State(), 3_000, 0).index) // 1080p30 needs 2.8
        assertEquals(2, next(State(), 1_500, 0).index) // 720p30 needs 1.24
        assertEquals(4, next(State(), 300, 0).index) // nothing fits: the lowest step
    }

    @Test
    fun aTargetAtTheViewersOwnCapIsNotABadLink() {
        // Manual 4 Mbit/s at 1080p60 (needs 5.6): the target sits at the cap, the link is fine.
        assertEquals(0, next(State(), 3_900, 0, capBps = 4_000_000).index)
        // Well below the cap it is the link.
        assertEquals(2, next(State(), 1_500, 0, capBps = 4_000_000).index)
        // A degraded viewer whose target reaches its cap climbs back.
        val held = State(index = 2, changedAtMs = 0)
        val once = next(held, 3_950, MIN_HOLD_MS, capBps = 4_000_000)
        assertEquals(1, next(once, 3_950, MIN_HOLD_MS + 2_000, capBps = 4_000_000).index)
    }

    @Test
    fun risesOneStepAfterTheHoldAndTwoGoodPolls() {
        val low = next(State(), 300, 0) // index 4 at t=0
        assertEquals(4, low.index)
        // Within the hold nothing rises, however good the target.
        assertEquals(4, next(low, 20_000, MIN_HOLD_MS - 1).index)
        // After the hold: two polls with 1.25x the budget of 720p15 (0.62 Mbit/s).
        val once = next(low, 800, MIN_HOLD_MS)
        assertEquals(4, once.index)
        assertEquals(3, next(once, 800, MIN_HOLD_MS + 2_000).index)
        // A poll without the margin resets the count.
        assertEquals(4, next(once, 700, MIN_HOLD_MS + 2_000).index)
    }

    @Test
    fun aRiseThatFailsDoublesTheNextHold() {
        val low = next(State(), 300, 0)
        val rose = next(next(low, 800, MIN_HOLD_MS), 800, MIN_HOLD_MS + 2_000)
        assertEquals(3, rose.index)
        // The link could not carry it: back down within the hold.
        val fell = next(rose, 300, MIN_HOLD_MS + 4_000)
        assertEquals(4, fell.index)
        assertEquals(2 * MIN_HOLD_MS, fell.holdMs)
        // So the next rise waits twice as long.
        assertEquals(4, next(fell, 800, MIN_HOLD_MS + 4_000 + MIN_HOLD_MS).index)
    }

    @Test
    fun remapKeepsTheQualityWhenTheCaptureChanges() {
        // Thermal: 1080p60 capture becomes 720p60. A viewer on 720p15 stays on 720p15, not 720p10.
        val oldRungs = rungs
        val newRungs = QualityLadder.rungs(720, 1152, 60, sharp = true) // 720p60, 720p30, 720p15, 720p10
        assertEquals(2, QualityLadder.remap(oldRungs, w, h, 3, newRungs, 720, 1152))
        // A viewer on 1080p30 gets 720p30 (no better than before in size and frame rate).
        assertEquals(1, QualityLadder.remap(oldRungs, w, h, 1, newRungs, 720, 1152))
        // Full quality stays full quality.
        assertEquals(0, QualityLadder.remap(oldRungs, w, h, 0, newRungs, 720, 1152))
    }
}
