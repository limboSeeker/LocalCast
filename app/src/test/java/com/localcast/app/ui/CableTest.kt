package com.localcast.app.ui

import com.localcast.app.service.AddressKind
import com.localcast.app.service.NetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CableTest {

    private val wifi = NetAddress("192.168.1.23", "wlan0", AddressKind.WIFI)
    private val hotspot = NetAddress("10.10.0.1", "ap0", AddressKind.HOTSPOT)
    private val usb = NetAddress("192.168.42.129", "rndis0", AddressKind.USB)
    private val ncm = NetAddress("192.168.44.1", "ncm0", AddressKind.USB)
    private val vpn = NetAddress("10.8.0.2", "tun0", AddressKind.VPN)

    @Test
    fun picksTheUsbAddressBesideWifi() {
        assertEquals("http://192.168.42.129:8080", cableUrl(listOf(wifi, usb, vpn), 8080))
    }

    @Test
    fun firstUsbAddressWins() {
        assertEquals("http://192.168.42.129:9000", cableUrl(listOf(hotspot, usb, ncm), 9000))
    }

    @Test
    fun cableOnlyStillShowsTheRow() {
        assertEquals("http://192.168.42.129:8080", cableUrl(listOf(usb), 8080))
    }

    @Test
    fun noCableNoRow() {
        assertNull(cableUrl(listOf(wifi, hotspot, vpn), 8080))
        assertNull(cableUrl(emptyList(), 8080))
    }

    @Test
    fun noPortNoRow() {
        assertNull(cableUrl(listOf(wifi, usb), null))
    }
}
