package com.localcast.app.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolSenderTest {
    @Test
    fun `sender message is encoded with type and omits nulls`() {
        val json = Protocol.encodeServer(ServerMessage.Sender(encoder = "c2.qti.avc.encoder", hardware = true, encodeMs = 3.5))
        assertTrue(json, json.contains("\"type\":\"sender\""))
        assertTrue(json, json.contains("\"encoder\":\"c2.qti.avc.encoder\""))
        assertTrue(json, !json.contains("sendDelayMs"))
    }

    @Test
    fun `report accepts latencyEstimated`() {
        val msg = Protocol.decodeClient("""{"type":"report","stats":{"latencyMs":72.5,"latencyEstimated":true}}""")
        val stats = (msg as ClientMessage.Report).stats
        assertEquals(72.5, stats.latencyMs!!, 0.001)
        assertEquals(true, stats.latencyEstimated)
    }
}
