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
 * `maxFramerate` and `scaleResolutionDownBy`. A new size resets the hardware encoder (a keyframe),
 * so steps are held for a while and a step up that did not last makes the next hold longer.
 */
object QualityLadder {

    /** One step: the capture scaled down by [scale] at [fps] frames per second. */
    data class Rung(val scale: Double, val fps: Int) {
        /** Short side of a [width]x[height] capture at this step. */
        fun shortSide(width: Int, height: Int): Int = (min(width, height) / scale).roundToInt()

        /** e.g. "720p30" for a [width]x[height] capture. */
        fun label(width: Int, height: Int): String = "${shortSide(width, height)}p$fps"
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
     * A target this close to the viewer's own bitrate cap is limited by the cap (a manual bitrate,
     * its share of the Wi-Fi), not by the link: that never steps down.
     */
    const val CAP_LIMITED_SHARE = 0.85

    /** No step up sooner than this after any change; doubled (up to [MAX_HOLD_MS]) when a rise fails. */
    const val MIN_HOLD_MS = 15_000L
    const val MAX_HOLD_MS = 120_000L

    /**
     * Steps from best to worst for a [width]x[height] capture at [fps]. Frame-rate steps come
     * before size steps where possible: they need no encoder reset.
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

    /**
     * Current step, the polls so far that would allow a step up, when the step last changed and
     * how long to hold it before rising.
     */
    data class State(
        val index: Int = 0,
        val riseVotes: Int = 0,
        val changedAtMs: Long = Long.MIN_VALUE / 2,
        val holdMs: Long = MIN_HOLD_MS,
    )

    /**
     * Next state for the encoder's [targetBps] at [nowMs]; [capBps] is the viewer's own bitrate cap.
     * Down at once to the best step that fits (a step fits when its budget is covered, or when the
     * target sits at the cap: then the link is not the limit). Up one step only after the hold
     * time, when the target exceeds that step's budget by [RISE_MARGIN] for [RISE_POLLS] polls.
     * Falling again within the hold after a rise doubles the next hold.
     */
    fun next(
        rungs: List<Rung>,
        width: Int,
        height: Int,
        state: State,
        targetBps: Int,
        capBps: Int,
        nowMs: Long,
    ): State {
        if (rungs.isEmpty()) return State()
        val current = state.index.coerceIn(0, rungs.lastIndex)
        val capLimited = targetBps >= capBps * CAP_LIMITED_SHARE
        fun fits(i: Int) = capLimited || needBps(rungs[i], width, height) <= targetBps
        val fit = rungs.indices.firstOrNull { fits(it) } ?: rungs.lastIndex
        if (fit > current) {
            // A rise that did not hold: wait longer before the next one.
            val failedRise = nowMs - state.changedAtMs < state.holdMs && state.riseVotes == -1
            val hold = if (failedRise) min(state.holdMs * 2, MAX_HOLD_MS) else state.holdMs
            return State(fit, 0, nowMs, hold)
        }
        val holding = nowMs - state.changedAtMs < state.holdMs
        if (current == 0) {
            // Keep the "just rose" mark through the hold (a fall then still counts as a failed
            // rise); after a steady hold at full quality, forget past failures.
            return if (holding) state.copy(index = 0) else state.copy(index = 0, riseVotes = 0, holdMs = MIN_HOLD_MS)
        }
        if (holding) return state.copy(index = current, riseVotes = votesAfterRise(state))
        val upper = needBps(rungs[current - 1], width, height)
        if (capLimited || targetBps >= upper * RISE_MARGIN) {
            val votes = max(0, state.riseVotes) + 1
            // riseVotes = -1 marks "just rose": a fall within the hold counts as a failed rise.
            return if (votes >= RISE_POLLS) State(current - 1, -1, nowMs, state.holdMs) else state.copy(index = current, riseVotes = votes)
        }
        // Held steady through a whole hold: forget past failures.
        return state.copy(index = current, riseVotes = 0, holdMs = MIN_HOLD_MS)
    }

    private fun votesAfterRise(state: State) = if (state.riseVotes == -1) -1 else 0

    /**
     * Index in [newRungs] for a viewer that was on [oldIndex] of [oldRungs] when the capture size
     * or frame rate changed (thermal limits): the best new step no better than the old one in size
     * and frame rate, so a change never silently raises or lowers the quality.
     */
    fun remap(
        oldRungs: List<Rung>,
        oldWidth: Int,
        oldHeight: Int,
        oldIndex: Int,
        newRungs: List<Rung>,
        newWidth: Int,
        newHeight: Int,
    ): Int {
        if (oldIndex <= 0 || oldRungs.isEmpty() || newRungs.isEmpty()) return 0
        val old = oldRungs[oldIndex.coerceIn(0, oldRungs.lastIndex)]
        val side = old.shortSide(oldWidth, oldHeight)
        return newRungs.indices.firstOrNull {
            newRungs[it].shortSide(newWidth, newHeight) <= side && newRungs[it].fps <= old.fps
        } ?: newRungs.lastIndex
    }
}
