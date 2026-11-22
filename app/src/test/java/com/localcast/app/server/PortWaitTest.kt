package com.localcast.app.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket

class PortWaitTest {

    @Test
    fun waitsUntilThePortIsReleased() {
        var checks = 0
        var slept = 0L
        val free = waitForPort(8080, 3_000, isFree = { ++checks >= 4 }, sleep = { slept += it })
        assertTrue(free)
        assertEquals(4, checks)
        assertEquals(3 * PORT_POLL_MS, slept)
    }

    @Test
    fun givesUpAfterTheWait() {
        var slept = 0L
        assertFalse(waitForPort(8080, 1_000, isFree = { false }, sleep = { slept += it }))
        assertEquals(1_000L, slept)
    }

    @Test
    fun realSocketReleasedDuringTheWaitIsTaken() {
        val holder = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(0)) }
        val port = holder.localPort
        val probe: (Int) -> Boolean = { p ->
            try {
                ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress(p)) }
                true
            } catch (e: java.io.IOException) {
                false
            }
        }
        assertFalse(probe(port))
        Thread { Thread.sleep(500); holder.close() }.start()
        assertTrue(waitForPort(port, 3_000, probe))
    }
}
