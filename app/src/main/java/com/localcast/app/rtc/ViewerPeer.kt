package com.localcast.app.rtc

import android.util.Log
import com.localcast.app.server.DenyReason
import com.localcast.app.server.IceCandidateDto
import com.localcast.app.server.ServerMessage
import com.localcast.app.server.ViewerStats
import com.localcast.app.service.SenderStats
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.Locale

/**
 * One browser viewer = one send-only PeerConnection sharing the engine's VideoTrack.
 * Every method hops onto the engine's single thread, so PeerConnection access is serialised.
 */
internal class ViewerPeer(
    override val viewerId: String,
    private val remoteAddress: String,
    private val engine: RtcEngine,
    private val send: (ServerMessage) -> Unit,
) : ViewerSession {

    private var pc: PeerConnection? = null
    private var sender: RtpSender? = null
    private var remoteDescriptionSet = false
    private val pendingRemoteIce = ArrayList<IceCandidate>()
    private var generation = 0
    private var closed = false
    private var connected = false

    /** This PeerConnection reached CONNECTED at least once (a later DISCONNECTED is transient). */
    private var everConnected = false

    /** Lite software stream beyond the hardware encoder budget: VP8, reduced size (engine thread). */
    var softwareEncoder = false

    /** Stats showed this viewer on the hardware H.264 encoder (engine thread). */
    var hardwareConfirmed = false

    /** ICE/DTLS connected (engine thread). */
    val isConnected: Boolean get() = connected

    /** Still setting up its first connection: its encoder may not have started yet (engine thread). */
    val isStarting: Boolean get() = !everConnected

    // Outbound bitrate from successive stats samples.
    private var lastBytesSent = -1L
    private var lastStatsTimestampUs = 0.0
    private var lastEncodeTimeS = -1.0
    private var lastFramesEncoded = -1L
    private var lastSendDelayS = -1.0
    private var lastPacketsSent = -1L

    /** Called by the engine (on its thread) right after creation. */
    fun negotiate() {
        if (closed) return
        disposePeerConnection()
        val gen = ++generation
        remoteDescriptionSet = false
        hardwareConfirmed = false
        everConnected = false
        pendingRemoteIce.clear()

        val peer = engine.factory.createPeerConnection(engine.rtcConfiguration(), Observer(gen))
        if (peer == null) {
            Log.e(TAG, "$viewerId: createPeerConnection returned null")
            return
        }
        pc = peer
        val transceiver = peer.addTransceiver(
            engine.videoTrack,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf(STREAM_ID)),
        )
        engine.applyCodecPreferences(transceiver, softwareEncoder)
        sender = transceiver.sender
        applySendParameters()

        peer.createOffer(object : SdpAdapter("createOffer") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                engine.post {
                    if (gen != generation || closed) return@post
                    peer.setLocalDescription(object : SdpAdapter("setLocal") {
                        override fun onSetSuccess() {
                            engine.post {
                                if (gen == generation && !closed) send(ServerMessage.Offer(sdp.description))
                            }
                        }
                    }, sdp)
                }
            }
        }, MediaConstraints())
    }

    /**
     * Bitrate, frame rate, size and degradation preference from the current capture settings and
     * this viewer's share of the Wi-Fi. Called again whenever viewers join or leave.
     */
    fun applySendParameters() {
        val peer = pc ?: return
        val s = sender ?: return
        val p = engine.sendProfile
        val share = engine.shares
        try {
            val params = s.parameters
            params.degradationPreference = p.degradation
            val maxBps = if (softwareEncoder) share.liteBps else share.fullBps
            params.encodings.forEach { e ->
                e.maxBitrateBps = maxBps
                e.minBitrateBps = CaptureFormat.MIN_BITRATE_BPS
                // Software VP8 at full size would eat the CPU the capture pipeline needs.
                e.maxFramerate = if (softwareEncoder) p.liteFps else p.fps
                e.scaleResolutionDownBy = if (softwareEncoder) p.liteScale else 1.0
                e.networkPriority = org.webrtc.Priority.HIGH
                e.bitratePriority = 4.0
            }
            if (!s.setParameters(params)) Log.w(TAG, "$viewerId: setParameters rejected")
            // A start bitrate resets the bandwidth estimate, so it is only given before the first
            // connection (not after a transient DISCONNECTED).
            val start = if (everConnected) null else engine.startBitrateFor(this, maxBps)
            peer.setBitrate(CaptureFormat.MIN_BITRATE_BPS, start, maxBps)
        } catch (e: RuntimeException) {
            Log.w(TAG, "$viewerId: applySendParameters failed", e)
        }
    }

    override fun onAnswer(sdp: String) = engine.post {
        val peer = pc ?: return@post
        Log.i(
            DIAG_TAG,
            "$viewerId answer extmap: " +
                sdp.lineSequence().filter { it.startsWith("a=extmap:") }.joinToString(" ") { it.substringAfter(' ') },
        )
        val gen = generation
        peer.setRemoteDescription(object : SdpAdapter("setRemote") {
            override fun onSetSuccess() {
                engine.post {
                    if (gen != generation || closed) return@post
                    remoteDescriptionSet = true
                    pendingRemoteIce.forEach { peer.addIceCandidate(it) }
                    pendingRemoteIce.clear()
                    // Re-apply after negotiation: encodings are only fully populated now.
                    applySendParameters()
                }
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    override fun onRemoteIce(candidate: IceCandidateDto) = engine.post {
        if (candidate.candidate.isBlank()) return@post // end-of-candidates marker
        val ice = IceCandidate(candidate.sdpMid ?: "0", candidate.sdpMLineIndex, candidate.candidate)
        val peer = pc ?: return@post
        if (remoteDescriptionSet) peer.addIceCandidate(ice) else pendingRemoteIce.add(ice)
    }

    override fun restart() = engine.post {
        Log.i(TAG, "$viewerId: restart requested by viewer")
        connected = false
        engine.onPeerConnectionState(viewerId, false)
        negotiate()
    }

    override fun onReport(stats: ViewerStats) {
        Log.i(
            DIAG_TAG,
            "$viewerId viewer fps=${stats.fps.f0()} ${stats.width}x${stats.height} jb=${stats.jitterBufferMs.f0()}ms " +
                "dec=${stats.decodeMs.f0()}ms rtt=${stats.rttMs.f0()}ms lat=${stats.latencyMs.f0()}ms" +
                (if (stats.latencyEstimated == true) "(est)" else "") +
                " dropped=${stats.framesDropped} lost=${stats.packetsLost} codec=${stats.codec}",
        )
        engine.onViewerReport(viewerId, stats)
    }

    // Previous cumulative counters for the diagnostic log line.
    private val diagPrev = HashMap<String, Double>()

    /**
     * One logcat line per stats poll (`adb logcat -s LocalCast/Diag`): where frames are lost between
     * capture (media-source), encoder and network, plus bandwidth estimate and send-side delays.
     */
    private fun logDiagnostics(stats: Collection<RTCStats>, outbound: RTCStats) {
        val out = outbound.members
        val src = stats.firstOrNull { it.type == "media-source" && it.members["kind"] == "video" }?.members
        val pair = stats.firstOrNull {
            it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded"
        }?.members
        fun n(map: Map<String, Any>?, key: String) = (map?.get(key) as? Number)?.toDouble()
        val now = mapOf(
            "t" to outbound.timestampUs / 1e6,
            "src" to n(src, "frames"),
            "enc" to n(out, "framesEncoded"),
            "sent" to n(out, "framesSent"),
            "key" to n(out, "keyFramesEncoded"),
            "huge" to n(out, "hugeFramesSent"),
            "bytes" to n(out, "bytesSent"),
            "encT" to n(out, "totalEncodeTime"),
            "pkts" to n(out, "packetsSent"),
            "sendT" to n(out, "totalPacketSendDelay"),
            "rtx" to n(out, "retransmittedPacketsSent"),
            "nack" to n(out, "nackCount"),
            "pli" to n(out, "pliCount"),
            "qp" to n(out, "qpSum"),
        )
        val prev = HashMap(diagPrev)
        diagPrev.clear()
        now.forEach { (k, v) -> if (v != null) diagPrev[k] = v }
        val t = now["t"] ?: return
        val dt = prev["t"]?.let { t - it }?.takeIf { it > 0 } ?: return
        // Counters only grow; a negative delta means they were reset, so skip that sample.
        fun d(k: String): Double? = now[k]?.let { v -> prev[k]?.let { v - it } }?.takeIf { it >= 0 }
        fun perS(k: String): Double? = d(k)?.div(dt)
        fun per(k: String, by: String, scale: Double): Double? =
            d(by)?.takeIf { it > 0 }?.let { div -> d(k)?.div(div)?.times(scale) }
        Log.i(
            DIAG_TAG,
            "$viewerId src=${perS("src").f1()}fps enc=${perS("enc").f1()}fps sent=${perS("sent").f1()}fps " +
                "key+${d("key").i()} huge+${d("huge").i()} " +
                "out=${perS("bytes")?.times(8 / 1000.0).f0()}kbps target=${n(out, "targetBitrate")?.div(1000).f0()}kbps " +
                "avail=${n(pair, "availableOutgoingBitrate")?.div(1000).f0()}kbps " +
                "encMs=${per("encT", "enc", 1000.0).f1()} pacerMs=${per("sendT", "pkts", 1000.0).f1()} " +
                "qp=${per("qp", "enc", 1.0).f1()} rtx+${d("rtx").i()} nack+${d("nack").i()} pli+${d("pli").i()} " +
                (if (softwareEncoder) "sw " else "") +
                "rtt=${n(pair, "currentRoundTripTime")?.times(1000).f0()}ms " +
                "ql=${out["qualityLimitationReason"]} ${n(out, "frameWidth").i()}x${n(out, "frameHeight").i()}",
        )
    }

    private fun Double?.f0() = this?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "-"
    private fun Double?.f1() = this?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-"
    private fun Double?.i() = this?.toLong()?.toString() ?: "-"

    override fun close() = engine.post { closeNow() }

    /** Engine thread only. The device cannot serve another viewer: say so and close. */
    fun rejectBusy() {
        if (closed) return
        send(ServerMessage.Denied(DenyReason.BUSY))
        closeNow()
    }

    /** Engine thread only. */
    fun closeNow() {
        if (closed) return
        closed = true
        disposePeerConnection()
        engine.onPeerClosed(this)
    }

    /** Engine thread only. Collects outbound stats and reports them to the engine. */
    fun pollStats() {
        val peer = pc ?: return
        val s = sender ?: return
        if (!connected) return
        peer.getStats(s) { report -> engine.post { handleStats(report) } }
    }

    private fun handleStats(report: RTCStatsReport) {
        if (closed) return
        val stats = report.statsMap.values
        val outbound = stats.firstOrNull { it.type == "outbound-rtp" && it.members["kind"] == "video" } ?: return
        val m = outbound.members
        val bytes = (m["bytesSent"] as? Number)?.toLong()
        var bitrateKbps: Double? = null
        if (bytes != null && lastBytesSent >= 0 && outbound.timestampUs > lastStatsTimestampUs) {
            val seconds = (outbound.timestampUs - lastStatsTimestampUs) / 1e6
            bitrateKbps = (bytes - lastBytesSent) * 8 / 1000.0 / seconds
        }
        if (bytes != null) {
            lastBytesSent = bytes
            lastStatsTimestampUs = outbound.timestampUs
        }
        val encodeTime = (m["totalEncodeTime"] as? Number)?.toDouble()
        val framesEncoded = (m["framesEncoded"] as? Number)?.toLong()
        var encodeMs: Double? = null
        if (encodeTime != null && framesEncoded != null) {
            if (lastFramesEncoded >= 0 && framesEncoded > lastFramesEncoded) {
                encodeMs = (encodeTime - lastEncodeTimeS) / (framesEncoded - lastFramesEncoded) * 1000.0
            }
            lastEncodeTimeS = encodeTime
            lastFramesEncoded = framesEncoded
        }
        val sendDelay = (m["totalPacketSendDelay"] as? Number)?.toDouble()
        val packetsSent = (m["packetsSent"] as? Number)?.toLong()
        var sendDelayMs: Double? = null
        if (sendDelay != null && packetsSent != null) {
            if (lastPacketsSent >= 0 && packetsSent > lastPacketsSent) {
                sendDelayMs = (sendDelay - lastSendDelayS) / (packetsSent - lastPacketsSent) * 1000.0
            }
            lastSendDelayS = sendDelay
            lastPacketsSent = packetsSent
        }
        val codecId = m["codecId"] as? String
        val codec = codecId?.let { report.statsMap[it]?.members?.get("mimeType") as? String }?.substringAfter('/')
        val implementation = m["encoderImplementation"] as? String
        val powerEfficient = m["powerEfficientEncoder"] as? Boolean
        val remoteInbound = stats.firstOrNull { it.type == "remote-inbound-rtp" && it.members["kind"] == "video" }
        val rtt = (remoteInbound?.members?.get("roundTripTime") as? Number)?.toDouble()
            ?: stats.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true }
                ?.members?.get("currentRoundTripTime").let { (it as? Number)?.toDouble() }

        val hardware = powerEfficient ?: implementation?.let { engine.isHardwareImplementation(it) }
        val fps = (m["framesPerSecond"] as? Number)?.toDouble()
        send(ServerMessage.Sender(implementation, hardware, encodeMs, sendDelayMs, fps))
        runCatching { logDiagnostics(stats, outbound) }.onFailure { Log.w(DIAG_TAG, "$viewerId: diagnostics failed", it) }
        engine.onSenderStats(
            viewerId,
            SenderStats(
                codec = codec,
                encoderImplementation = implementation,
                hardwareEncoder = hardware,
                fps = fps,
                bitrateKbps = bitrateKbps,
                width = (m["frameWidth"] as? Number)?.toInt(),
                height = (m["frameHeight"] as? Number)?.toInt(),
                qualityLimitation = m["qualityLimitationReason"] as? String,
                rttMs = rtt?.let { it * 1000.0 },
                encodeMs = encodeMs,
                sendDelayMs = sendDelayMs,
            ),
        )
        // Last: this may turn the viewer away (closes the peer).
        engine.onPeerEncoder(this, codec, hardware)
    }

    private fun disposePeerConnection() {
        val peer = pc ?: return
        pc = null
        sender = null
        connected = false
        lastBytesSent = -1
        lastFramesEncoded = -1
        lastPacketsSent = -1
        diagPrev.clear()
        try {
            peer.dispose()
        } catch (e: RuntimeException) {
            Log.w(TAG, "$viewerId: dispose failed", e)
        }
    }

    private inner class Observer(private val gen: Int) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            engine.post {
                if (gen != generation || closed) return@post
                send(ServerMessage.Ice(IceCandidateDto(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)))
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            engine.post {
                if (gen != generation || closed) return@post
                Log.i(TAG, "$viewerId ($remoteAddress): $newState")
                val nowConnected = newState == PeerConnection.PeerConnectionState.CONNECTED
                if (nowConnected) everConnected = true
                if (nowConnected != connected) {
                    connected = nowConnected
                    engine.onPeerConnectionState(viewerId, nowConnected)
                }
                if (nowConnected) engine.onViewerConnected()
            }
        }

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            Log.i(TAG, "$viewerId: selected pair ${event.local?.sdp} <-> ${event.remote?.sdp}")
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private open inner class SdpAdapter(private val op: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "$viewerId: $op failed: $error")
        }

        override fun onSetFailure(error: String?) {
            Log.e(TAG, "$viewerId: $op failed: $error")
        }
    }

    private companion object {
        const val TAG = "LocalCast/Peer"
        const val DIAG_TAG = "LocalCast/Diag"
        const val STREAM_ID = "localcast"
    }
}
