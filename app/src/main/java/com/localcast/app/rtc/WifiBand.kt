package com.localcast.app.rtc

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

internal object WifiBand {

    /** Frequency (MHz) of the device's Wi-Fi client connection, or null when there is none or it is unknown. */
    fun currentMhz(context: Context): Int? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        // Any Wi-Fi network, not only the default one: with mobile data as default the LAN is still Wi-Fi.
        @Suppress("DEPRECATION")
        val fromCaps = cm.allNetworks.firstNotNullOfOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstNotNullOfOrNull null
            // API 31+: WifiInfo comes with the capabilities (SSID/BSSID redacted, frequency intact).
            (caps.transportInfo as? WifiInfo)?.frequency?.takeIf { it > 0 }
        }
        if (fromCaps != null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return@runCatching fromCaps
        // API 30: transportInfo is not populated for Wi-Fi; frequency is not location-protected.
        @Suppress("DEPRECATION")
        context.getSystemService(WifiManager::class.java).connectionInfo?.frequency?.takeIf { it > 0 }
    }.getOrNull()
}
