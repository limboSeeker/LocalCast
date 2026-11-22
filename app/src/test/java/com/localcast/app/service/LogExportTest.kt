package com.localcast.app.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.Calendar
import java.util.Date

class LogExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun date(): Date = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.NOVEMBER, 22, 9, 5, 7)
    }.time

    @Test
    fun fileNameHasSortableTimestamp() {
        assertEquals("localcast-20261122-090507.txt", LogExport.fileName(date()))
    }

    @Test
    fun headerCarriesVersionDiagnosticsAndMarker() {
        val h = LogExport.header("LocalCast 1.1.1 (3)\ndevice: X\n\n", "1.1.1 (3)", date())
        val lines = h.lines()
        assertEquals("LocalCast log", lines[0])
        assertEquals("version: 1.1.1 (3)", lines[1])
        assertTrue(lines[2].startsWith("time: 2026-11-22 09:05:07"))
        assertTrue(h.contains("device: X\n\n----- logcat -----\n"))
    }

    @Test
    fun shortLogIsKeptWhole() {
        val bytes = "a\nb\n".toByteArray()
        val (out, cut) = LogExport.trimToLast(bytes, 100)
        assertArrayEquals(bytes, out)
        assertEquals(0L, cut)
    }

    @Test
    fun longLogKeepsTheLastWholeLines() {
        val text = "first line\nsecond\nthird\n"
        val (out, cut) = LogExport.trimToLast(text.toByteArray(), 10)
        // The last 10 bytes start inside "second": that partial line is dropped too.
        assertEquals("third\n", String(out))
        assertEquals((text.length - "third\n".length).toLong(), cut)
    }

    @Test
    fun streamTailIsBoundedAndLineAligned() {
        val line = "11-22 09:05:07.123  1234  1250 I LocalCast: frame sent\n"
        val text = line.repeat(20_000) // ~1.1 MB
        val max = 64 * 1024
        val (out, dropped) = LogExport.readTail(ByteArrayInputStream(text.toByteArray()), max)
        assertTrue(out.size <= max)
        assertTrue(String(out).startsWith("11-22"))
        assertTrue(String(out).endsWith(line))
        assertEquals(text.length.toLong(), dropped + out.size)
    }

    @Test
    fun streamUnderCapIsUntouched() {
        val text = "one\ntwo\n"
        val (out, dropped) = LogExport.readTail(ByteArrayInputStream(text.toByteArray()), 1024)
        assertEquals(text, String(out))
        assertEquals(0L, dropped)
    }

    @Test
    fun pruneKeepsNewestFiles() {
        val dir = tmp.newFolder("logs")
        listOf("120000", "120001", "120002", "120003", "120004").forEach {
            dir.resolve("localcast-20261122-$it.txt").writeText("x")
        }
        LogExport.pruneOld(dir, 3)
        assertEquals(
            listOf("localcast-20261122-120002.txt", "localcast-20261122-120003.txt", "localcast-20261122-120004.txt"),
            dir.list()!!.sorted(),
        )
    }
}
