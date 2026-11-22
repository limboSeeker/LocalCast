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

/** Why the broadcast is paused: viewers see a "paused" card and black frames meanwhile. */
enum class PauseReason {
    /** The user pressed Pause (app or notification). */
    USER,

    /** The screen went off or is locked ("pause on lock" setting); resumes by itself on unlock. */
    LOCKED,
}

/** Engine-side load figures, reported every ~2 s while casting. */
data class LoadInfo(
    val viewers: Int,
    /** Viewers on the reduced software VP8 stream (beyond the hardware encoders). */
    val liteViewers: Int,
    /** Distinct devices turned away (device at capacity) in the last minute. */
    val turnedAway: Int,
    /** What the Wi-Fi link is assumed to carry for all viewers together. */
    val linkKbps: Int,
    /** Current bitrate cap of one normal viewer (its share of the link). */
    val perViewerCapKbps: Int,
    val hardwareEncoders: Int,
    /** 0 normal, 1 warm, 2 hot, 3 critical. */
    val thermalLevel: Int,
    /** One-line technical summary for "Copy diagnostics". */
    val summary: String,
    /** Frequency (MHz) of the device's Wi-Fi client link, or null: hotspot only, Ethernet or unknown. */
    val wifiMhz: Int? = null,
)

enum class HealthLevel { OK, WARN, PROBLEM }

/** The most important thing to tell the user about the broadcast right now (one at a time). */
enum class HealthIssue {
    NONE,

    /** Some devices could not join: encoders/CPU used up. */
    AT_CAPACITY,

    /** The device is hot: frame rate / resolution reduced. */
    HOT,

    /** The Wi-Fi does not keep up: quality reduced. */
    NETWORK,

    /** At least one viewer sees the picture with a noticeable delay. */
    LATENCY,
}

/** Summary card "Broadcast health" on the live screen. */
data class BroadcastHealth(
    val level: HealthLevel,
    val issue: HealthIssue,
    val viewers: Int,
    val liteViewers: Int,
    val turnedAway: Int,
    val totalKbps: Int,
    val linkKbps: Int,
    val worstLatencyMs: Int?,
    val thermalLevel: Int,
    val hardwareEncoders: Int,
    /** See [LoadInfo.wifiMhz]. */
    val wifiMhz: Int? = null,
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
    /** User-facing, already localized (resolved with getString in the service). */
    val error: String? = null,
    val addresses: List<NetAddress> = emptyList(),
    val port: Int? = null,
    val pin: String? = null,
    val pinRequired: Boolean = true,
    val viewers: List<ViewerInfo> = emptyList(),
    val capture: CaptureInfo? = null,
    /** Non-null while paused. */
    val pause: PauseReason? = null,
    val health: BroadcastHealth? = null,
    val load: LoadInfo? = null,
    /** Hardware encoder capabilities line (EncoderProbe), for "Copy diagnostics". */
    val encoderCaps: String? = null,
) {
    val isPaused: Boolean get() = pause != null

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
