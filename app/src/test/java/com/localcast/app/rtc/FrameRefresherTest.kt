package com.localcast.app.rtc

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRefresherTest {
    @Test
    fun repeatsFastWhileSharpeningThenSlower() {
        assertEquals(100L, FrameRefresher.idleDelayMs(0))
        assertEquals(100L, FrameRefresher.idleDelayMs(1_999))
        assertEquals(250L, FrameRefresher.idleDelayMs(2_000))
        assertEquals(250L, FrameRefresher.idleDelayMs(600_000))
        // A slower base (debug knob) is never made faster.
        assertEquals(500L, FrameRefresher.idleDelayMs(10_000, idleRefreshMs = 500))
    }
}
