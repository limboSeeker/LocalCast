package com.localcast.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.localcast.app.server.NetAddresses
import com.localcast.app.service.AddressKind
import com.localcast.app.service.NetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the network chip on the main screen says. */
enum class NetworkKind {
    WIFI_5, WIFI_6, WIFI_24,

    /** Wi-Fi interface is up but its band is unknown (e.g. Wi-Fi without internet, not the default). */
    WIFI,
    HOTSPOT, ETHERNET,

    /** USB tethering, VPN, … — reachable, but nothing to advise about. */
    OTHER,
    NONE,
    ;

    /** 2.4 GHz is crowded and slow: frame drops are likely. */
    val isWarning: Boolean get() = this == WIFI_24
    val isMissing: Boolean get() = this == NONE
}

/** Snapshot of the default network as far as the chip cares. Never needs location (no SSID). */
private sealed interface DefaultNet {
    data class Wifi(val frequencyMhz: Int?) : DefaultNet
    data object Ethernet : DefaultNet
    data object Other : DefaultNet
    data object None : DefaultNet
}

/**
 * Network the viewers will reach this device through. [castAddresses] are the live broadcast's
 * addresses (empty when idle; then the interfaces are polled here, because a hotspot's softap
 * interface is not a ConnectivityManager network).
 */
@Composable
fun rememberNetworkKind(castAddresses: List<NetAddress>): NetworkKind {
    val context = LocalContext.current
    // Seeded synchronously so the chip doesn't flash "Немає мережі" before the first callback.
    var default by remember { mutableStateOf(currentDefault(context)) }
    DisposableEffect(context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                default = classify(context, caps)
            }

            override fun onLost(network: Network) {
                default = DefaultNet.None
            }
        }
        // The callback fires immediately with the current default network, if any.
        runCatching { cm.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper())) }
        onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }

    var polled by remember { mutableStateOf<List<NetAddress>>(emptyList()) }
    val owner = LocalLifecycleOwner.current
    val idle = castAddresses.isEmpty()
    LaunchedEffect(idle, owner) {
        if (!idle) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                polled = withContext(Dispatchers.IO) { runCatching { NetAddresses.current() }.getOrDefault(emptyList()) }
                delay(POLL_MS)
            }
        }
    }

    val addresses = if (idle) polled else castAddresses
    return resolve(default, addresses)
}

private fun currentDefault(context: Context): DefaultNet {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return DefaultNet.None
    return classify(context, caps)
}

private fun resolve(default: DefaultNet, addresses: List<NetAddress>): NetworkKind {
    val kinds = addresses.map { it.kind }.toSet()
    if (default is DefaultNet.Wifi) return band(default.frequencyMhz)
    return when {
        AddressKind.HOTSPOT in kinds -> NetworkKind.HOTSPOT
        AddressKind.WIFI in kinds -> NetworkKind.WIFI
        default == DefaultNet.Ethernet || AddressKind.ETHERNET in kinds -> NetworkKind.ETHERNET
        kinds.isNotEmpty() -> NetworkKind.OTHER
        else -> NetworkKind.NONE
    }
}

private fun band(mhz: Int?): NetworkKind = when (mhz) {
    null -> NetworkKind.WIFI
    in 2400..2500 -> NetworkKind.WIFI_24
    in 4900..5900 -> NetworkKind.WIFI_5
    in 5925..7125 -> NetworkKind.WIFI_6
    else -> NetworkKind.WIFI
}

private fun classify(context: Context, caps: NetworkCapabilities): DefaultNet = when {
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> DefaultNet.Wifi(wifiFrequency(context, caps))
    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> DefaultNet.Ethernet
    // Mobile data alone is useless for a LAN broadcast: report it as "no network".
    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> DefaultNet.None
    else -> DefaultNet.Other
}

private fun wifiFrequency(context: Context, caps: NetworkCapabilities): Int? {
    // API 31+: WifiInfo comes with the capabilities (SSID/BSSID redacted, frequency intact).
    val fromCaps = (caps.transportInfo as? WifiInfo)?.frequency
    if (fromCaps != null && fromCaps > 0) return fromCaps
    // API 30: transportInfo is not populated for Wi-Fi; frequency is not location-protected.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return null
    @Suppress("DEPRECATION")
    val legacy = runCatching { context.getSystemService(WifiManager::class.java).connectionInfo?.frequency }.getOrNull()
    return legacy?.takeIf { it > 0 }
}

private const val POLL_MS = 4_000L
