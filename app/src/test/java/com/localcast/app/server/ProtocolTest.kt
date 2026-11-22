package com.localcast.app.server

import com.localcast.app.service.AddressKind
import com.localcast.app.service.NetAddress
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
            ServerMessage.Welcome("t", "v1", addresses = listOf(AddressDto("192.168.42.129", "usb")), port = 8080),
            ServerMessage.Addresses(listOf(AddressDto("192.168.1.5", "wifi"), AddressDto("10.0.0.2", "other", 8081)), 8080),
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

    @Test fun addressesWireForm() {
        assertEquals(
            """{"type":"addresses","addresses":[{"ip":"192.168.42.129","kind":"usb"}],"port":8080}""",
            Protocol.encodeServer(ServerMessage.Addresses(listOf(AddressDto("192.168.42.129", "usb")), 8080)),
        )
        // Old clients/servers: the new fields have defaults on both sides.
        val w = Protocol.json.decodeFromString(ServerMessage.serializer(), """{"type":"welcome","token":"t","viewerId":"v1"}""")
        assertEquals(ServerMessage.Welcome("t", "v1"), w)
        val info = Protocol.json.decodeFromString(ServerInfo.serializer(), """{"name":"n","pinRequired":true,"streaming":false}""")
        assertEquals(emptyList<AddressDto>(), info.addresses)
        assertEquals(null, info.port)
        val withAddr = Protocol.json.encodeToString(ServerInfo.serializer(), info.copy(addresses = listOf(AddressDto("1.2.3.4", "wifi")), port = 8080))
        assertTrue(withAddr, withAddr.contains("\"addresses\":[{\"ip\":\"1.2.3.4\",\"kind\":\"wifi\"}],\"port\":8080"))
    }

    @Test fun netAddressToDto() {
        val list = listOf(
            NetAddress("192.168.42.129", "rndis0", AddressKind.USB),
            NetAddress("192.168.1.5", "wlan0", AddressKind.WIFI),
            NetAddress("192.168.43.1", "swlan0", AddressKind.HOTSPOT),
            NetAddress("10.0.0.5", "eth0", AddressKind.ETHERNET),
            NetAddress("10.8.0.2", "tun0", AddressKind.VPN),
            NetAddress("192.168.49.1", "p2p0", AddressKind.OTHER),
        ).toDtos()
        assertEquals(listOf("usb", "wifi", "hotspot", "ethernet", "vpn", "other"), list.map { it.kind })
        assertEquals(AddressDto("192.168.42.129", "usb"), list[0])
    }

    @Test fun unknownFieldsIgnored() {
        val m = Protocol.decodeClient("""{"type":"hello","pin":"1","extra":{"a":1},"userAgent":"x"}""")
        assertEquals(ClientMessage.Hello(pin = "1", userAgent = "x"), m)
        val r = Protocol.decodeClient("""{"type":"report","stats":{"fps":30,"future":1}}""") as ClientMessage.Report
        assertEquals(30.0, r.stats.fps!!, 0.0)
    }
}
