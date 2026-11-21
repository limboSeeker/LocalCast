package com.localcast.app.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val ice = IceCandidateDto("candidate:1 1 udp 1 1.2.3.4 5 typ host", "0", 0)

    @Test fun clientRoundTrip() {
        val msgs = listOf(
            ClientMessage.Hello(pin = "123456", userAgent = "UA"),
            ClientMessage.Hello(token = "tok"),
            ClientMessage.Answer("v=0"),
            ClientMessage.Ice(ice),
            ClientMessage.Restart,
            ClientMessage.Report(ViewerStats(fps = 59.8, width = 1080, codec = "H264", packetsLost = 2)),
            ClientMessage.Bye,
        )
        for (m in msgs) {
            val text = Protocol.json.encodeToString(ClientMessage.serializer(), m)
            assertEquals(m, Protocol.decodeClient(text))
        }
    }

    @Test fun serverRoundTrip() {
        val msgs = listOf(
            ServerMessage.Welcome("t", "v1"),
            ServerMessage.Denied(DenyReason.LOCKED, 60),
            ServerMessage.Denied(DenyReason.FULL),
            ServerMessage.Offer("v=0"),
            ServerMessage.Ice(ice),
            ServerMessage.State(true, 1080, 2400, 60),
            ServerMessage.Bye("stopped"),
        )
        for (m in msgs) {
            val text = Protocol.encodeServer(m)
            assertEquals(m, Protocol.json.decodeFromString(ServerMessage.serializer(), text))
        }
    }

    @Test fun exactStrings() {
        assertTrue(Protocol.json.encodeToString(ClientMessage.serializer(), ClientMessage.Hello(pin = "1")).contains("\"type\":\"hello\""))
        val denied = Protocol.encodeServer(ServerMessage.Denied(DenyReason.NOT_STREAMING))
        assertTrue(denied, denied.contains("\"reason\":\"not_streaming\""))
        assertTrue(denied, !denied.contains("retryAfterSec"))
        assertTrue(Protocol.encodeServer(ServerMessage.Denied(DenyReason.LOCKED, 60)).contains("\"retryAfterSec\":60"))
        assertEquals("{\"type\":\"bye\"}", Protocol.json.encodeToString(ClientMessage.serializer(), ClientMessage.Bye))
    }

    @Test fun unknownFieldsIgnored() {
        val m = Protocol.decodeClient("""{"type":"hello","pin":"1","extra":{"a":1},"userAgent":"x"}""")
        assertEquals(ClientMessage.Hello(pin = "1", userAgent = "x"), m)
        val r = Protocol.decodeClient("""{"type":"report","stats":{"fps":30,"future":1}}""") as ClientMessage.Report
        assertEquals(30.0, r.stats.fps!!, 0.0)
    }
}
