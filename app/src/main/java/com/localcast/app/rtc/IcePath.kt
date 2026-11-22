package com.localcast.app.rtc

import com.localcast.app.service.AddressKind
import com.localcast.app.service.NetAddress

/** Which local path a viewer uses and which of our ICE candidates it should get. Pure, unit-tested. */
object IcePath {

    /** The viewer's WebSocket arrived on our USB tether address: its video should stay on the cable. */
    fun isUsb(localAddress: String?, addresses: List<NetAddress>): Boolean =
        localAddress != null && addresses.any { it.ip == localAddress && it.kind == AddressKind.USB }

    /** Connection address of an ICE candidate line ("candidate:… 1 udp 2122260223 192.168.42.129 51234 typ host …"). */
    fun candidateAddress(sdp: String): String? =
        sdp.trim().split(' ').getOrNull(4)

    /**
     * Whether to send this local candidate to a viewer. A viewer on the cable gets only candidates
     * of the cable's address, so ICE cannot settle on Wi-Fi while the cable works (a pulled cable
     * closes the session and the page reconnects over Wi-Fi). Everybody else gets all candidates.
     */
    fun shouldSend(candidateSdp: String, viaUsb: Boolean, localAddress: String?): Boolean =
        !viaUsb || localAddress == null || candidateAddress(candidateSdp) == localAddress

    /**
     * Whether to use a viewer's candidate. A viewer on the cable: only its addresses in the cable's
     * subnet (/24) or hidden ones (mDNS .local — the browser's checks then arrive over the cable),
     * so a pair over Wi-Fi is never built from our side.
     */
    fun shouldUseRemote(candidateSdp: String, viaUsb: Boolean, localAddress: String?): Boolean {
        if (!viaUsb || localAddress == null) return true
        val addr = candidateAddress(candidateSdp) ?: return true
        if (addr.endsWith(".local")) return true
        val subnet = localAddress.substringBeforeLast('.', "")
        return subnet.isNotEmpty() && addr.substringBeforeLast('.', "") == subnet
    }
}
