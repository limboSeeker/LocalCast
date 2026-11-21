package com.localcast.app.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Signaling contract between the phone (server, WebRTC offerer) and the browser viewer.
 * Transport: WebSocket `/ws`, one JSON object per text frame, discriminator field `type`.
 * Human-readable spec: docs/PROTOCOL.md — keep both in sync.
 */
object Protocol {
    const val VERSION = 1

    val json: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun decodeClient(text: String): ClientMessage = json.decodeFromString(ClientMessage.serializer(), text)

    fun encodeServer(message: ServerMessage): String = json.encodeToString(ServerMessage.serializer(), message)
}

/** Browser → phone. */
@Serializable
sealed interface ClientMessage {
    /** First message after the socket opens. Either [pin] (first visit) or [token] (reconnect). */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val pin: String? = null,
        val token: String? = null,
        val userAgent: String? = null,
    ) : ClientMessage

    @Serializable
    @SerialName("answer")
    data class Answer(val sdp: String) : ClientMessage

    @Serializable
    @SerialName("ice")
    data class Ice(val candidate: IceCandidateDto) : ClientMessage

    /** Viewer asks for a fresh PeerConnection (ICE failed / disconnected too long). */
    @Serializable
    @SerialName("restart")
    data object Restart : ClientMessage

    /** Periodic viewer-side metrics (every ~2 s) shown in the phone UI. */
    @Serializable
    @SerialName("report")
    data class Report(val stats: ViewerStats) : ClientMessage

    @Serializable
    @SerialName("bye")
    data object Bye : ClientMessage
}

/** Phone → browser. */
@Serializable
sealed interface ServerMessage {
    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val token: String,
        val viewerId: String,
        val protocol: Int = Protocol.VERSION,
    ) : ServerMessage

    @Serializable
    @SerialName("denied")
    data class Denied(
        val reason: DenyReason,
        val retryAfterSec: Int? = null,
    ) : ServerMessage

    @Serializable
    @SerialName("offer")
    data class Offer(val sdp: String) : ServerMessage

    @Serializable
    @SerialName("ice")
    data class Ice(val candidate: IceCandidateDto) : ServerMessage

    /** Stream parameters changed (rotation, settings) or broadcast paused. */
    @Serializable
    @SerialName("state")
    data class State(
        val streaming: Boolean,
        val width: Int,
        val height: Int,
        val fps: Int,
    ) : ServerMessage

    /** Phone-side encoder info for the viewer's stats overlay and latency estimate (every ~2 s). */
    @Serializable
    @SerialName("sender")
    data class Sender(
        val encoder: String? = null,
        val hardware: Boolean? = null,
        /** Average time the encoder spent per frame. */
        val encodeMs: Double? = null,
        /** Average time packets waited in the pacer before being sent. */
        val sendDelayMs: Double? = null,
        val fps: Double? = null,
    ) : ServerMessage

    /** Server is closing this session (broadcast stopped). Viewer should not auto-reconnect quickly. */
    @Serializable
    @SerialName("bye")
    data class Bye(val reason: String) : ServerMessage
}

@Serializable
enum class DenyReason {
    /** Wrong PIN. */
    @SerialName("pin") PIN,

    /** Too many wrong PINs from this IP; see retryAfterSec. */
    @SerialName("locked") LOCKED,

    /** Unknown / expired token — ask the user for the PIN again. */
    @SerialName("token") TOKEN,

    /** Viewer limit reached. */
    @SerialName("full") FULL,

    /** Broadcast is not running (server up but no capture). */
    @SerialName("not_streaming") NOT_STREAMING,

    /** Malformed handshake. */
    @SerialName("bad_request") BAD_REQUEST,
}

@Serializable
data class IceCandidateDto(
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = 0,
)

@Serializable
data class ViewerStats(
    val fps: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrateKbps: Double? = null,
    /** Capture→display latency: measured (Chrome rVFC captureTime) or estimated from pipeline parts. */
    val latencyMs: Double? = null,
    /** true when [latencyMs] is an estimate rather than a measurement. */
    val latencyEstimated: Boolean? = null,
    val rttMs: Double? = null,
    val jitterBufferMs: Double? = null,
    val decodeMs: Double? = null,
    val framesDropped: Long? = null,
    val packetsLost: Long? = null,
    val codec: String? = null,
)

/** Payload of `GET /api/info` (no auth), lets the page decide whether to show the PIN form. */
@Serializable
data class ServerInfo(
    val name: String,
    val pinRequired: Boolean,
    val streaming: Boolean,
    val protocol: Int = Protocol.VERSION,
)
