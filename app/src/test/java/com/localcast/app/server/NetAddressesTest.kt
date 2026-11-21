package com.localcast.app.server

import com.localcast.app.service.AddressKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetAddressesTest {
    @Test fun classify() {
        assertEquals(AddressKind.WIFI, NetAddresses.classify("wlan0"))
        assertEquals(AddressKind.HOTSPOT, NetAddresses.classify("ap0"))
        assertEquals(AddressKind.HOTSPOT, NetAddresses.classify("swlan0"))
        assertEquals(AddressKind.HOTSPOT, NetAddresses.classify("softap0"))
        assertEquals(AddressKind.ETHERNET, NetAddresses.classify("eth0"))
        assertEquals(AddressKind.USB, NetAddresses.classify("rndis0"))
        assertEquals(AddressKind.USB, NetAddresses.classify("ncm0"))
        assertEquals(AddressKind.VPN, NetAddresses.classify("tun0"))
        assertEquals(AddressKind.OTHER, NetAddresses.classify("p2p-wlan0-0"))
        assertEquals(AddressKind.OTHER, NetAddresses.classify("foo0"))
        for (n in listOf("lo", "rmnet_data0", "ccmni1", "v4-rmnet0", "clat4", "dummy0")) assertNull(n, NetAddresses.classify(n))
    }

    @Test fun selectFiltersAndSorts() {
        val r = NetAddresses.select(
            listOf(
                "tun0" to "10.8.0.2",
                "rmnet0" to "100.64.1.2",
                "wlan0" to "169.254.3.3",
                "wlan0" to "8.8.4.4",
                "wlan0" to "192.168.1.20",
                "ap0" to "192.168.43.1",
                "eth0" to "172.20.0.5",
                "foo0" to "10.0.0.1",
                "rndis0" to "192.168.42.129",
                "eth1" to "192.168.1.20",
                "lo" to "127.0.0.1",
            ),
        )
        assertEquals(
            listOf("192.168.1.20", "8.8.4.4", "192.168.43.1", "172.20.0.5", "192.168.42.129", "10.0.0.1", "10.8.0.2"),
            r.map { it.ip },
        )
        assertEquals(AddressKind.WIFI, r[0].kind)
        assertEquals("wlan0", r[0].interfaceName)
    }
}
