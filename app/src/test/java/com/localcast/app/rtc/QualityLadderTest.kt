package com.localcast.app.rtc

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

    private fun next(state: State, kbps: Int) = QualityLadder.next(rungs, w, h, state, kbps * 1000)

    @Test
    fun fallsStraightToTheStepThatFits() {
        assertEquals(State(0, 0), next(State(), 8_000)) // 1080p60 needs 5.6 Mbit/s
        assertEquals(State(1, 0), next(State(), 3_000)) // 1080p30 needs 2.8
        assertEquals(State(2, 0), next(State(), 1_500)) // 720p30 needs 1.24
        assertEquals(State(4, 0), next(State(), 300)) // nothing fits: the lowest step
    }

    @Test
    fun risesOneStepAfterTwoGoodPolls() {
        val low = State(4, 0)
        // 720p15 needs 0.62 Mbit/s; rising needs 1.25x of it.
        val once = next(low, 800)
        assertEquals(State(4, 1), once)
        assertEquals(State(3, 0), next(once, 800))
        // A poll without the margin resets the count.
        assertEquals(State(4, 0), next(State(4, 1), 700))
        // Even with plenty of bitrate it climbs one step at a time.
        assertEquals(State(3, 0), next(State(4, 1), 20_000))
    }

    @Test
    fun stepIndexIsClampedWhenTheLadderShrinks() {
        val short = QualityLadder.rungs(720, 1152, 30, sharp = true) // 3 steps
        assertEquals(State(2, 0), QualityLadder.next(short, 720, 1152, State(4, 0), 100_000))
    }
}
