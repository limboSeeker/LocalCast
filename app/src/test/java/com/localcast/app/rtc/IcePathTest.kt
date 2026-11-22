package com.localcast.app.rtc

import com.localcast.app.service.AddressKind
import com.localcast.app.service.NetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IcePathTest {
    private val usb = NetAddress("192.168.42.129", "rndis0", AddressKind.USB)
    private val wifi = NetAddress("192.168.0.107", "wlan0", AddressKind.WIFI)
    private val usbCandidate = "candidate:1 1 udp 2122260223 192.168.42.129 51234 typ host generation 0"
    private val wifiCandidate = "candidate:2 1 udp 2122194687 192.168.0.107 40000 typ host generation 0"

    @Test
    fun aViewerOnTheCableIsRecognisedByTheLocalAddress() {
        assertTrue(IcePath.isUsb("192.168.42.129", listOf(wifi, usb)))
        assertFalse(IcePath.isUsb("192.168.0.107", listOf(wifi, usb)))
        assertFalse(IcePath.isUsb(null, listOf(usb)))
    }

    @Test
    fun cableViewersGetOnlyCableCandidates() {
        assertEquals("192.168.42.129", IcePath.candidateAddress(usbCandidate))
        assertTrue(IcePath.shouldSend(usbCandidate, viaUsb = true, localAddress = "192.168.42.129"))
        assertFalse(IcePath.shouldSend(wifiCandidate, viaUsb = true, localAddress = "192.168.42.129"))
        // Wi-Fi viewers get everything, as before.
        assertTrue(IcePath.shouldSend(usbCandidate, viaUsb = false, localAddress = "192.168.0.107"))
        assertTrue(IcePath.shouldSend(wifiCandidate, viaUsb = false, localAddress = "192.168.0.107"))
    }
}
