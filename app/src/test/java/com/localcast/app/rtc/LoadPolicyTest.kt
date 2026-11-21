package com.localcast.app.rtc

import com.localcast.app.rtc.LoadPolicy.Tier
import com.localcast.app.rtc.ThermalGovernor.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadPolicyTest {

    @Test
    fun admitTriesHardwareWhileTheLimitIsUnknown() {
        assertEquals(Tier.HARDWARE, LoadPolicy.admit(true, null, 12, 0, 2))
    }

    @Test
    fun admitFallsBackToLiteThenTurnsAway() {
        assertEquals(Tier.HARDWARE, LoadPolicy.admit(true, 6, 5, 0, 2))
        assertEquals(Tier.SOFTWARE, LoadPolicy.admit(true, 6, 6, 0, 2))
        assertEquals(Tier.SOFTWARE, LoadPolicy.admit(true, 6, 6, 1, 2))
        assertEquals(Tier.BUSY, LoadPolicy.admit(true, 6, 6, 2, 2))
    }

    @Test
    fun admitWithoutHardwareUsesTheSoftwareBudgetOnly() {
        assertEquals(Tier.SOFTWARE, LoadPolicy.admit(false, null, 0, 3, 4))
        assertEquals(Tier.BUSY, LoadPolicy.admit(false, null, 0, 4, 4))
    }

    @Test
    fun softwareBudgetFollowsCoresAndHeat() {
        assertEquals(2, LoadPolicy.softwareBudget(8, deviceHasHardware = true, heat = Level.NORMAL))
        assertEquals(1, LoadPolicy.softwareBudget(4, deviceHasHardware = true, heat = Level.NORMAL))
        assertEquals(1, LoadPolicy.softwareBudget(2, deviceHasHardware = true, heat = Level.NORMAL))
        assertEquals(3, LoadPolicy.softwareBudget(16, deviceHasHardware = true, heat = Level.NORMAL))
        assertEquals(1, LoadPolicy.softwareBudget(8, deviceHasHardware = true, heat = Level.HOT))
        assertEquals(0, LoadPolicy.softwareBudget(8, deviceHasHardware = true, heat = Level.CRITICAL))
        // No hardware encoder: VP8 is everybody's path, so more sessions, and never zero.
        assertEquals(4, LoadPolicy.softwareBudget(8, deviceHasHardware = false, heat = Level.NORMAL))
        assertEquals(1, LoadPolicy.softwareBudget(8, deviceHasHardware = false, heat = Level.CRITICAL))
    }

    @Test
    fun linkBudgetByBand() {
        assertEquals(16_000_000, LoadPolicy.linkBudgetBps(2412))
        assertEquals(60_000_000, LoadPolicy.linkBudgetBps(5180))
        assertEquals(60_000_000, LoadPolicy.linkBudgetBps(6115))
        assertEquals(20_000_000, LoadPolicy.linkBudgetBps(null))
    }

    @Test
    fun singleViewerKeepsTheWholeProfile() {
        val s = LoadPolicy.shares(20_000_000, 16_000_000, fullViewers = 1, liteViewers = 0)
        assertEquals(20_000_000, s.fullBps)
    }

    @Test
    fun severalViewersShareTheLink() {
        val s = LoadPolicy.shares(20_000_000, 16_000_000, fullViewers = 8, liteViewers = 0)
        assertEquals(2_000_000, s.fullBps)
        // Never below the floor, never above the profile.
        assertEquals(LoadPolicy.MIN_SHARE_BPS, LoadPolicy.shares(20_000_000, 16_000_000, 40, 0).fullBps)
        assertEquals(8_000_000, LoadPolicy.shares(8_000_000, 60_000_000, 3, 0).fullBps)
    }

    @Test
    fun liteViewersTakeTheirCapFirst() {
        val s = LoadPolicy.shares(20_000_000, 16_000_000, fullViewers = 2, liteViewers = 2)
        assertEquals(LoadPolicy.LITE_MAX_BPS, s.liteBps)
        assertEquals((16_000_000 - 2 * LoadPolicy.LITE_MAX_BPS) / 2, s.fullBps)
        assertTrue(2 * s.fullBps + 2 * s.liteBps <= 16_000_000)
    }

    @Test
    fun joinersStartGently() {
        assertEquals(CaptureFormat.startBitrateBps(20_000_000), LoadPolicy.startBps(20_000_000, othersWatching = 0))
        assertEquals(LoadPolicy.JOIN_START_BPS, LoadPolicy.startBps(20_000_000, othersWatching = 3))
        // A low cap is never raised by the join rule.
        assertEquals(CaptureFormat.startBitrateBps(1_000_000), LoadPolicy.startBps(1_000_000, othersWatching = 3))
    }

    @Test
    fun liteStreamSize() {
        assertEquals(3120 / 1280.0, LoadPolicy.liteScale(1440, 3120), 1e-9)
        assertEquals(1.0, LoadPolicy.liteScale(720, 1280), 1e-9)
        assertEquals(1.0, LoadPolicy.liteScale(480, 640), 1e-9)
        assertEquals(30, LoadPolicy.liteFps(60, Level.NORMAL))
        assertEquals(15, LoadPolicy.liteFps(30, Level.WARM))
        assertEquals(10, LoadPolicy.liteFps(10, Level.NORMAL))
    }
}
