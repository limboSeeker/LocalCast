package com.localcast.app.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginRuleTest {
    private val own = listOf("192.168.1.5", "192.168.42.129")
    private fun allowed(origin: String, host: String?) = originAllowed(origin, host) { own }

    @Test fun sameOriginAllowed() {
        assertTrue(allowed("http://192.168.1.5:8080", "192.168.1.5:8080"))
        assertTrue(allowed("http://Phone.local:8080", "phone.local:8080"))
    }

    @Test fun otherOwnAddressSamePortAllowed() {
        // Page from Wi-Fi, socket on USB, and back.
        assertTrue(allowed("http://192.168.1.5:8080", "192.168.42.129:8080"))
        assertTrue(allowed("http://192.168.42.129:8080", "192.168.1.5:8080"))
        assertTrue(allowed("http://192.168.1.5", "192.168.42.129")) // default port on both sides
        assertTrue(allowed("http://192.168.1.5:80", "192.168.42.129"))
    }

    @Test fun foreignOriginsRejected() {
        assertFalse(allowed("http://evil.example:8080", "192.168.1.5:8080"))
        assertFalse(allowed("http://192.168.1.77:8080", "192.168.1.5:8080")) // another LAN host
        assertFalse(allowed("http://192.168.42.129:9000", "192.168.1.5:8080")) // own IP, other port (another app)
        assertFalse(allowed("https://192.168.42.129:8080", "192.168.1.5:8080"))
        assertFalse(allowed("null", "192.168.1.5:8080"))
        assertFalse(allowed("not a uri", "192.168.1.5:8080"))
        assertFalse(allowed("http://192.168.1.5:8080", null))
    }

    @Test fun ownIpsResolvedOnlyWhenNeeded() {
        var calls = 0
        assertTrue(originAllowed("http://192.168.1.5:8080", "192.168.1.5:8080") { calls++; own })
        assertFalse(originAllowed("http://evil.example:9", "192.168.1.5:8080") { calls++; own })
        assertEquals(0, calls)
        assertTrue(originAllowed("http://192.168.1.5:8080", "192.168.42.129:8080") { calls++; own })
        assertEquals(1, calls)
    }

    @Test fun plainIpUnwrapsMappedAddresses() {
        assertEquals("192.168.42.129", plainIp("::ffff:192.168.42.129"))
        assertEquals("192.168.42.129", plainIp("/192.168.42.129"))
        assertEquals("192.168.1.5", plainIp("[::FFFF:192.168.1.5]"))
        assertEquals("fe80::1", plainIp("fe80::1%wlan0"))
        assertEquals(null, plainIp("localhost"))
        assertEquals(null, plainIp(""))
        assertEquals(null, plainIp(null))
    }
}
