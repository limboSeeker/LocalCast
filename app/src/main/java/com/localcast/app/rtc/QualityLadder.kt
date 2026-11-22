package com.localcast.app.rtc

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Per-viewer quality steps for a narrow or unstable link. Pure logic, unit-tested.
 *
 * Measured on a tablet over busy 2.4 GHz Wi-Fi: the bandwidth estimate fell to 0.3 Mbit/s and the
 * encoder kept ~40 fps at qp 42–51 (unreadable text). Spending the budget on fewer frames and, if
 * still short, fewer pixels keeps every frame readable; sending less also lets the estimate climb
 * back. A forced estimate floor was tried and made it worse (the link really carried ~2.7 Mbit/s).
 *
 * The capture is shared by all viewers and never restarted: a step only changes this viewer's
 * `maxFramerate` and `scaleResolutionDownBy`.
 */
object QualityLadder {

    /** One step: the capture scaled down by [scale] at [fps] frames per second. */
    data class Rung(val scale: Double, val fps: Int) {
        /** e.g. "720p30" for a [width]x[height] capture. */
        fun label(width: Int, height: Int): String = "${(min(width, height) / scale).roundToInt()}p$fps"
    }

    /** Bits per pixel per frame that keep scrolling text readable in H.264 screen content. */
    const val BITS_PER_PIXEL = 0.05

    /** Short side of the reduced size: sharpness first keeps text at 720p; smoothness goes to 540p. */
    const val SHARP_SHORT_SIDE = 720
    const val SMOOTH_SHORT_SIDE = 540

    /** Rising needs this margin over the next step's budget, two polls in a row. */
    const val RISE_MARGIN = 1.25
    const val RISE_POLLS = 2

    /**
     * Steps from best to worst for a [width]x[height] capture at [fps].
     * Sharpness first (the user's order): full size at fps → full size at 30 → 720p 30 → 720p 15 → 720p 10.
     * Smoothness first: full size → 540p at fps → 540p 30 → 540p 15.
     */
    fun rungs(width: Int, height: Int, fps: Int, sharp: Boolean): List<Rung> {
        val short = max(1, min(width, height))
        fun scaleTo(side: Int) = if (short > side * 1.1) short / side.toDouble() else 1.0
        val f30 = min(fps, 30)
        val steps = if (sharp) {
            val s = scaleTo(SHARP_SHORT_SIDE)
            listOf(Rung(1.0, fps), Rung(1.0, f30), Rung(s, f30), Rung(s, min(fps, 15)), Rung(s, min(fps, 10)))
        } else {
            val s = scaleTo(SMOOTH_SHORT_SIDE)
            listOf(Rung(1.0, fps), Rung(s, fps), Rung(s, f30), Rung(s, min(fps, 15)))
        }
        return steps.distinct()
    }

    /** Bitrate a [rung] of a [width]x[height] capture needs to stay readable. */
    fun needBps(rung: Rung, width: Int, height: Int): Double =
        (width / rung.scale) * (height / rung.scale) * rung.fps * BITS_PER_PIXEL

    /** Current step and the polls so far that would allow a step up. */
    data class State(val index: Int = 0, val riseVotes: Int = 0)

    /**
     * Next state for the encoder's [targetBps]: straight down to the best step that fits, up one
     * step only when the target exceeds that step's budget by [RISE_MARGIN] for [RISE_POLLS] polls,
     * so a sawing estimate does not swing between steps.
     */
    fun next(rungs: List<Rung>, width: Int, height: Int, state: State, targetBps: Int): State {
        if (rungs.isEmpty()) return State()
        val current = state.index.coerceIn(0, rungs.lastIndex)
        val fit = rungs.indices.firstOrNull { needBps(rungs[it], width, height) <= targetBps } ?: rungs.lastIndex
        if (fit > current) return State(fit, 0)
        if (current > 0 && targetBps >= needBps(rungs[current - 1], width, height) * RISE_MARGIN) {
            val votes = state.riseVotes + 1
            return if (votes >= RISE_POLLS) State(current - 1, 0) else State(current, votes)
        }
        return State(current, 0)
    }
}
