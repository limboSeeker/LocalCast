package com.localcast.app.rtc

import android.os.PowerManager
import com.localcast.app.rtc.ThermalGovernor.Level
import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalGovernorTest {
    @Test
    fun `status maps to levels`() {
        assertEquals(Level.NORMAL, ThermalGovernor.levelFor(PowerManager.THERMAL_STATUS_NONE, Float.NaN))
        assertEquals(Level.NORMAL, ThermalGovernor.levelFor(PowerManager.THERMAL_STATUS_LIGHT, 0.5f))
        assertEquals(Level.WARM, ThermalGovernor.levelFor(PowerManager.THERMAL_STATUS_MODERATE, Float.NaN))
        assertEquals(Level.HOT, ThermalGovernor.levelFor(PowerManager.THERMAL_STATUS_SEVERE, Float.NaN))
        assertEquals(Level.CRITICAL, ThermalGovernor.levelFor(PowerManager.THERMAL_STATUS_CRITICAL, Float.NaN))
        assertEquals(Level.CRITICAL, ThermalGovernor.levelFor(PowerManager.THERMAL_STATUS_SHUTDOWN, Float.NaN))
    }

    @Test
    fun `high headroom forecast warms up early`() {
        assertEquals(Level.WARM, ThermalGovernor.levelFor(PowerManager.THERMAL_STATUS_NONE, 0.9f))
        assertEquals(Level.NORMAL, ThermalGovernor.levelFor(PowerManager.THERMAL_STATUS_NONE, 0.84f))
    }
}
