package com.localcast.app.service

import com.localcast.app.server.ViewerStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class CastPhase { IDLE, STARTING, LIVE, STOPPING, ERROR }

enum class AddressKind { WIFI, HOTSPOT, ETHERNET, USB, VPN, OTHER }

data class NetAddress(
    val ip: String,
    val interfaceName: String,
    val kind: AddressKind,
)

/** Current capture geometry actually fed to the encoder. */
data class CaptureInfo(
    val width: Int,
    val height: Int,
    val fps: Int,
    /** 0 = normal; 1..3 = the stream is reduced because the device is warm / hot / critical. */
    val thermalLevel: Int = 0,
)

/** Aggregated outbound stats of one viewer's PeerConnection (from getStats). */
data class SenderStats(
    val codec: String? = null,
    /** e.g. "c2.qti.avc.encoder" or "libvpx"; null until the first frame is encoded. */
    val encoderImplementation: String? = null,
    val hardwareEncoder: Boolean? = null,
    val fps: Double? = null,
    val bitrateKbps: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** WebRTC qualityLimitationReason: none / bandwidth / cpu / other. */
    val qualityLimitation: String? = null,
    val rttMs: Double? = null,
    /** Average encode time per frame. */
    val encodeMs: Double? = null,
    /** Average pacer/send queue delay per packet. */
    val sendDelayMs: Double? = null,
)

data class ViewerInfo(
    val id: String,
    val remoteAddress: String,
    val userAgent: String?,
    /** ICE/DTLS connected and media flowing. */
    val connected: Boolean,
    val sender: SenderStats? = null,
    val viewer: ViewerStats? = null,
)

data class CastState(
    val phase: CastPhase = CastPhase.IDLE,
    /** Ukrainian, user-facing. */
    val error: String? = null,
    val addresses: List<NetAddress> = emptyList(),
    val port: Int? = null,
    val pin: String? = null,
    val pinRequired: Boolean = true,
    val viewers: List<ViewerInfo> = emptyList(),
    val capture: CaptureInfo? = null,
) {
    val isActive: Boolean get() = phase == CastPhase.STARTING || phase == CastPhase.LIVE

    /** First address the UI should show / encode in the QR code. */
    val primaryUrl: String?
        get() {
            val p = port ?: return null
            val a = addresses.firstOrNull() ?: return null
            return "http://${a.ip}:$p/"
        }

    /** URL for the QR code: PIN goes into the fragment (never sent to the server in a request line). */
    val qrUrl: String?
        get() {
            val base = primaryUrl ?: return null
            return if (pinRequired && pin != null) "$base#pin=$pin" else base
        }
}

/** Process-wide state shared between [CaptureService] and the UI. */
object CastStateStore {
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()

    fun update(transform: (CastState) -> CastState) = _state.update(transform)

    fun reset() {
        _state.value = CastState()
    }
}
