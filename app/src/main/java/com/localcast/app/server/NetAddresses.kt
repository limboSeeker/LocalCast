package com.localcast.app.server

import com.localcast.app.service.AddressKind
import com.localcast.app.service.NetAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/** Picks the IPv4 addresses a viewer on the LAN can use to reach this phone. */
object NetAddresses {
    private val ORDER = listOf(
        AddressKind.WIFI, AddressKind.HOTSPOT, AddressKind.ETHERNET,
        AddressKind.USB, AddressKind.OTHER, AddressKind.VPN,
    )
    private val EXCLUDED_PREFIXES = listOf("rmnet", "ccmni", "v4-", "clat", "dummy")

    fun current(): List<NetAddress> {
        val candidates = ArrayList<Pair<String, String>>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in ifaces) {
                try {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in nif.inetAddresses) {
                        if (addr is Inet4Address) candidates += nif.name to (addr.hostAddress ?: continue)
                    }
                } catch (_: SocketException) {
                    // interface vanished while enumerating — skip it
                }
            }
        } catch (_: SocketException) {
            return emptyList()
        }
        return select(candidates)
    }

    /** null = the interface must not be offered (mobile data, loopback, dummy). */
    fun classify(interfaceName: String): AddressKind? {
        val n = interfaceName.lowercase()
        if (n == "lo" || EXCLUDED_PREFIXES.any { n.startsWith(it) }) return null
        return when {
            n.startsWith("softap") || n.startsWith("swlan") || n.startsWith("ap") -> AddressKind.HOTSPOT
            n.startsWith("wlan") -> AddressKind.WIFI
            n.startsWith("eth") -> AddressKind.ETHERNET
            n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> AddressKind.USB
            n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("wg") || n.startsWith("ipsec") -> AddressKind.VPN
            n.startsWith("p2p") -> AddressKind.OTHER
            else -> AddressKind.OTHER
        }
    }

    /** Pure: (ifaceName, ipv4) → filtered, de-duplicated, best-first. */
    fun select(candidates: List<Pair<String, String>>): List<NetAddress> {
        val seen = HashSet<String>()
        return candidates
            .mapNotNull { (name, ip) ->
                val kind = classify(name) ?: return@mapNotNull null
                if (!isUsableIpv4(ip)) return@mapNotNull null
                NetAddress(ip, name, kind)
            }
            .sortedWith(compareBy<NetAddress>({ ORDER.indexOf(it.kind) }, { if (isPrivate(it.ip)) 0 else 1 }))
            .filter { seen.add(it.ip) }
    }

    private fun octets(ip: String): List<Int>? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return if (nums.all { it in 0..255 }) nums else null
    }

    private fun isUsableIpv4(ip: String): Boolean {
        val o = octets(ip) ?: return false
        if (o[0] == 127 || o[0] == 0) return false
        if (o[0] == 169 && o[1] == 254) return false
        return true
    }

    private fun isPrivate(ip: String): Boolean {
        val o = octets(ip) ?: return false
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
    }
}
