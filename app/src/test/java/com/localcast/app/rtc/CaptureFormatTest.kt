package com.localcast.app.rtc

import com.localcast.app.settings.FpsMode
import com.localcast.app.settings.ResolutionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFormatTest {

    private val always: (Int, Int, Int) -> Boolean = { _, _, _ -> true }

    @Test
    fun `720p and 1080p scale the short side and keep aspect`() {
        assertEquals(CaptureSize(720, 1600), CaptureFormat.targetSize(1080, 2400, ResolutionMode.P720))
        assertEquals(CaptureSize(1080, 2400), CaptureFormat.targetSize(1080, 2400, ResolutionMode.P1080))
        assertEquals(CaptureSize(1080, 1728), CaptureFormat.targetSize(1600, 2560, ResolutionMode.P1080))
        assertEquals(CaptureSize(720, 1560), CaptureFormat.targetSize(1080, 2340, ResolutionMode.P720))
    }

    @Test
    fun `landscape keeps orientation`() {
        assertEquals(CaptureSize(1600, 720), CaptureFormat.targetSize(2400, 1080, ResolutionMode.P720))
    }

    @Test
    fun `never upscales and native keeps display size`() {
        assertEquals(CaptureSize(720, 1280), CaptureFormat.targetSize(720, 1280, ResolutionMode.P1080))
        assertEquals(CaptureSize(1440, 3200), CaptureFormat.targetSize(1440, 3200, ResolutionMode.NATIVE))
    }

    @Test
    fun `sides are always even`() {
        val s = CaptureFormat.targetSize(1081, 2341, ResolutionMode.NATIVE)
        assertEquals(0, s.width % 2)
        assertEquals(0, s.height % 2)
        val t = CaptureFormat.targetSize(1080, 2333, ResolutionMode.P720)
        assertEquals(0, t.height % 2)
    }

    @Test
    fun `fitTo shrinks until supported`() {
        val fitted = CaptureFormat.fitTo(CaptureSize(1440, 3200)) { w, h -> w <= 1088 && h <= 2400 }
        assertTrue(fitted.width <= 1088 && fitted.height <= 2400)
        assertTrue(fitted.width >= 900) // ~10 % steps, not a collapse
    }

    @Test
    fun `software fallback is capped at 720p30`() {
        val (size, fps) = CaptureFormat.choose(
            CaptureSize(1080, 2400), ResolutionMode.NATIVE, FpsMode.FPS_60,
            preferSmooth = true, displayRefreshHz = 120f, hardwareEncoder = false, isSupported = always,
        )
        assertEquals(CaptureSize(720, 1600), size)
        assertEquals(30, fps)
    }

    @Test
    fun `auto uses 60 fps on a 60 Hz display when supported`() {
        val (size, fps) = CaptureFormat.choose(
            CaptureSize(1080, 2400), ResolutionMode.P1080, FpsMode.AUTO,
            preferSmooth = true, displayRefreshHz = 60f, hardwareEncoder = true, isSupported = always,
        )
        assertEquals(CaptureSize(1080, 2400), size)
        assertEquals(60, fps)
    }

    @Test
    fun `auto stays at 30 on a 50 Hz display and when 30 is forced`() {
        val a = CaptureFormat.choose(CaptureSize(1080, 2400), ResolutionMode.P1080, FpsMode.AUTO, true, 50f, true, always)
        assertEquals(30, a.second)
        val b = CaptureFormat.choose(CaptureSize(1080, 2400), ResolutionMode.P1080, FpsMode.FPS_30, true, 120f, true, always)
        assertEquals(30, b.second)
    }

    // Encoder that can do 60 fps only up to ~1080p60 macroblock rate (level 4.2: 489 600 MB/s).
    private val level42: (Int, Int, Int) -> Boolean = { w, h, fps ->
        val mbs = ((w + 15) / 16) * ((h + 15) / 16)
        mbs * fps <= 489_600 || fps <= 30
    }

    @Test
    fun `smooth priority trades a little resolution for 60 fps`() {
        val (size, fps) = CaptureFormat.choose(
            CaptureSize(1080, 2400), ResolutionMode.P1080, FpsMode.AUTO,
            preferSmooth = true, displayRefreshHz = 60f, hardwareEncoder = true, isSupported = level42,
        )
        assertEquals(60, fps)
        assertTrue(size.width in 810 until 1080)
    }

    @Test
    fun `sharp priority keeps resolution and drops to 30 fps`() {
        val (size, fps) = CaptureFormat.choose(
            CaptureSize(1080, 2400), ResolutionMode.P1080, FpsMode.AUTO,
            preferSmooth = false, displayRefreshHz = 60f, hardwareEncoder = true, isSupported = level42,
        )
        assertEquals(CaptureSize(1080, 2400), size)
        assertEquals(30, fps)
    }

    @Test
    fun `bitrate auto is clamped and manual is honoured`() {
        assertEquals(3_000_000, CaptureFormat.maxBitrateBps(0, CaptureSize(720, 1280), 30))
        assertEquals(20_000_000, CaptureFormat.maxBitrateBps(0, CaptureSize(1440, 3200), 60))
        val mid = CaptureFormat.maxBitrateBps(0, CaptureSize(1080, 2400), 60)
        assertTrue(mid in 10_000_000..14_000_000)
        assertEquals(12_000_000, CaptureFormat.maxBitrateBps(12, CaptureSize(720, 1280), 30))
        assertTrue(CaptureFormat.startBitrateBps(10_000_000) in 5_000_000..7_000_000)
    }
}
