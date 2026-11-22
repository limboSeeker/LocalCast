package com.localcast.app.rtc

import com.localcast.app.server.IceCandidateDto
import com.localcast.app.server.ServerMessage
import com.localcast.app.server.ViewerStats

/**
 * Boundary between signaling (server package) and the WebRTC engine (rtc package).
 * Signaling owns authentication and the WebSocket; the engine owns PeerConnections.
 */
interface ViewerSessionFactory {
    /** Whether screen capture is running and a viewer can be served right now. */
    val isStreaming: Boolean

    /**
     * Creates a PeerConnection for an authenticated viewer and immediately starts negotiation
     * (the engine sends [ServerMessage.Offer] and [ServerMessage.Ice] through [send]).
     * Returns null when not streaming. [send] is thread-safe and non-blocking.
     */
    /**
     * @param localAddress this device's IP the viewer's WebSocket arrived on (which interface:
     *     USB tether, Wi-Fi, hotspot...), or null when unknown.
     */
    fun createViewer(
        viewerId: String,
        remoteAddress: String,
        localAddress: String?,
        send: (ServerMessage) -> Unit,
    ): ViewerSession?
}

interface ViewerSession {
    val viewerId: String
    fun onAnswer(sdp: String)
    fun onRemoteIce(candidate: IceCandidateDto)

    /** Tear down the current PeerConnection and negotiate a new one (new offer). */
    fun restart()
    fun onReport(stats: ViewerStats)

    /** Idempotent. Releases the PeerConnection. */
    fun close()
}
