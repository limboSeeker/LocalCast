package com.localcast.app.rtc

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.localcast.app.server.NetAddresses
import com.localcast.app.server.ServerMessage
import com.localcast.app.server.ViewerStats
import com.localcast.app.service.CaptureInfo
import com.localcast.app.service.LoadInfo
import com.localcast.app.service.SenderStats
import com.localcast.app.settings.Priority
import com.localcast.app.settings.ResolutionMode
import com.localcast.app.settings.StreamSettings
import org.webrtc.CryptoOptions
import org.webrtc.EglBase
import org.webrtc.Logging
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.TunedVideoEncoderFactory
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.YuvConverter
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Owns screen capture and all PeerConnections. Every mutable field is confined to [executor]
 * (one thread), callbacks from libwebrtc threads are re-posted there.
 */
class RtcEngine(context: Context, private val listener: Listener) : ViewerSessionFactory {

    interface Listener {
        fun onCaptureInfo(info: CaptureInfo)

        /** Capture ended without [release] being called (system/user stopped the projection, or error). */
        fun onCaptureStopped(error: String?)
        fun onViewerConnection(viewerId: String, connected: Boolean)
        fun onSenderStats(viewerId: String, stats: SenderStats)
        fun onViewerReport(viewerId: String, stats: ViewerStats)

        /** Every ~2 s while casting: viewers, encoders, Wi-Fi split, heat. */
        fun onLoad(load: LoadInfo)

        /** Once at start: the hardware encoder's capabilities (for "Copy diagnostics"). */
        fun onEncoderCaps(line: String)
    }

    /** What every sender is configured with; recomputed whenever settings or geometry change. */
    data class SendProfile(
        val maxBitrateBps: Int,
        val startBitrateBps: Int,
        val fps: Int,
        val degradation: RtpParameters.DegradationPreference,
        /** Software overflow ("lite") streams: frame rate and scaleResolutionDownBy. */
        val liteFps: Int = minOf(fps, 30),
        val liteScale: Double = 1.0,
    )

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "LocalCast-RTC") }
    private val mainHandler = Handler(Looper.getMainLooper())

    internal lateinit var factory: PeerConnectionFactory
        private set
    internal lateinit var videoTrack: VideoTrack
        private set

    @Volatile internal var sendProfile = SendProfile(8_000_000, 4_800_000, 30, RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE)
        private set

    /** Per-viewer bitrate caps from splitting the Wi-Fi between the viewers ([LoadPolicy.shares]). */
    @Volatile internal var shares = LoadPolicy.Shares(sendProfile.maxBitrateBps, LoadPolicy.LITE_MAX_BPS)
        private set

    @Volatile override var isStreaming: Boolean = false
        private set

    private var eglBase: EglBase? = null
    private var encoderFactory: TunedVideoEncoderFactory? = null
    private var encoderCaps: MediaCodecInfo.VideoCapabilities? = null
    private var encoderProbe: EncoderCaps? = null
    private var thermal: ThermalGovernor? = null
    private var h264RetryTask: ScheduledFuture<*>? = null

    /** Assumed capacity of the Wi-Fi link for all viewers together (last [rebalance]). */
    private var linkBps = 0
    private var wifiMhz: Int? = null

    /** Devices (by address) turned away because the device was at capacity, and when. */
    private val turnedAwayAt = HashMap<String, Long>()

    /** Paused: the track sends black frames, viewers show a "paused" card. */
    private var paused = false

    /** Hardware sessions the device held when it last refused one, trusted for [SESSION_LIMIT_MS]. */
    private var sessionHardwareLimit: Int? = null
    private var sessionHardwareLimitUntilMs = 0L
    private var helper: SurfaceTextureHelper? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var refresher: FrameRefresher? = null
    private var windowContext: Context? = null
    private val peers = LinkedHashMap<String, ViewerPeer>()
    private var settings = StreamSettings()
    private var contentSize: CaptureSize? = null
    private var currentSize: CaptureSize? = null

    /** Current capture size (engine thread). */
    internal val captureSize: CaptureSize? get() = currentSize

    /** A/B switches, read when a broadcast starts (debug builds: adb setprop, see [DebugKnobs]). */
    @Volatile internal var qualityLadder = true

    /** FlexFEC offered to browsers that receive it (debug knob `fec`); see [fieldTrials]. */
    @Volatile private var flexFec = false
    private var currentFps = 0
    private var statsTask: ScheduledFuture<*>? = null
    private var released = false

    fun post(block: () -> Unit) {
        if (executor.isShutdown) return
        try {
            executor.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    Log.e(TAG, "engine task failed", t)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Log.w(TAG, "engine already shut down")
        }
    }

    /** Like [post], after [delayMs]. */
    fun postDelayed(delayMs: Long, block: () -> Unit) {
        if (executor.isShutdown) return
        try {
            executor.schedule({
                if (released) return@schedule
                try {
                    block()
                } catch (t: Throwable) {
                    Log.e(TAG, "engine task failed", t)
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Log.w(TAG, "engine already shut down")
        }
    }

    /** Starts capture. Must be called after the service entered the foreground (Android 14+ rule). */
    fun start(projectionData: Intent, initial: StreamSettings) = post {
        try {
            startInternal(projectionData, initial)
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            isStreaming = false
            listener.onCaptureStopped(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Pause: viewers get black frames (nothing private leaks) until resumed. */
    fun setPaused(value: Boolean) = post {
        if (paused == value || !::videoTrack.isInitialized) return@post
        paused = value
        videoTrack.setEnabled(!value)
        Log.i(TAG, if (value) "paused" else "resumed")
        if (!value) refresher?.requestFrame()
    }

    fun updateSettings(newSettings: StreamSettings) = post {
        settings = newSettings
        if (isStreaming) recomputeFormat()
    }

    override fun createViewer(
        viewerId: String,
        remoteAddress: String,
        localAddress: String?,
        send: (ServerMessage) -> Unit,
    ): ViewerSession? {
        if (!isStreaming) return null
        val viaUsb = IcePath.isUsb(localAddress, NetAddresses.current())
        val peer = ViewerPeer(viewerId, remoteAddress, this, localAddress, viaUsb, send)
        if (viaUsb) Log.i(DIAG_TAG, "$viewerId on the USB cable ($localAddress)")
        post {
            if (!isStreaming) {
                peer.closeNow()
                return@post
            }
            when (val tier = admission()) {
                LoadPolicy.Tier.BUSY -> {
                    Log.w(DIAG_TAG, "$viewerId turned away: ${loadLine()}")
                    turnAway(peer)
                    return@post
                }
                LoadPolicy.Tier.SOFTWARE, LoadPolicy.Tier.HARDWARE -> {
                    // On a device with hardware H.264 every VP8 stream is a lite overflow stream (also for a
            // browser without H.264). It stays lite until it reconnects, even if hardware frees up.
                    peer.softwareEncoder = tier == LoadPolicy.Tier.SOFTWARE && deviceHasHardware()
                    if (peer.softwareEncoder) Log.i(DIAG_TAG, "$viewerId on lite VP8: ${loadLine()}")
                }
            }
            // Admitted now: a refusal of this address a moment ago no longer counts.
            turnedAwayAt.remove(peer.remoteAddress)
            peers[viewerId] = peer
            rebalance()
            peer.negotiate()
        }
        return peer
    }

    /** Stops everything and waits (bounded) for teardown. Idempotent, call from any thread except the engine's. */
    fun release() {
        if (executor.isShutdown) return
        val done = CountDownLatch(1)
        post {
            try {
                shutdownInternal()
            } finally {
                done.countDown()
            }
        }
        done.await(3, TimeUnit.SECONDS)
        executor.shutdown()
    }

    // ---- internal: engine thread ----

    private fun startInternal(projectionData: Intent, initial: StreamSettings) {
        check(!released) { "engine released" }
        settings = initial
        ensureWebRtcInitialized(appContext)

        val egl = EglBase.create().also { eglBase = it }
        val guard = DebugKnobs.int("guard", 1) != 0
        qualityLadder = DebugKnobs.int("ladder", 1) != 0
        flexFec = DebugKnobs.int("fec", DEFAULT_FEC) != 0
        TunedVideoEncoderFactory.hevcAllowed = DebugKnobs.int("hevc", DEFAULT_HEVC) != 0
        TunedVideoEncoderFactory.minKeyFrameIntervalMs = DebugKnobs.int("key_interval_ms", 1000)
        TunedVideoEncoderFactory.maxOperatingRate = DebugKnobs.int("enc_max_rate", 0) != 0
        val trials = fieldTrials(DebugKnobs.int("bwe_trials", DEFAULT_BWE_TRIALS)) +
            if (flexFec) "WebRTC-FlexFEC-03/Enabled/WebRTC-FlexFEC-03-Advertised/Enabled/" else ""
        val encoders = TunedVideoEncoderFactory(egl.eglBaseContext, encoderListener, guard).also { encoderFactory = it }
        val probe = encoders.hardwareH264Name?.let(EncoderProbe::probe)
        encoderProbe = probe
        encoderCaps = probe?.videoCaps
        probe?.let {
            val caps = it.logLine(EncoderProbe.learnedBudget(appContext, it.codecName))
            Log.i(DIAG_TAG, caps)
            listener.onEncoderCaps(caps)
        }

        val adm = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options().apply {
                // The Android network monitor ignores interfaces it cannot classify (e.g. the
                // hotspot's ap0/swlan0) — and we never need network switching on a LAN.
                disableNetworkMonitor = true
            })
            .setFieldTrials(trials)
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(encoders)
            .setVideoDecoderFactory(SoftwareVideoDecoderFactory())
            .createPeerConnectionFactory()
        adm.release()

        val source = factory.createVideoSource(/* isScreencast= */ true).also { videoSource = it }
        val textureHelper = SurfaceTextureHelper.create("LocalCast-Capture", egl.eglBaseContext, true, YuvConverter())
            ?: error("SurfaceTextureHelper.create failed")
        helper = textureHelper
        val idleMs = DebugKnobs.int("idle_ms", FrameRefresher.DEFAULT_IDLE_REFRESH_MS.toInt()).toLong()
        val frames = FrameRefresher(source.capturerObserver, textureHelper, idleMs).also { refresher = it }
        thermal = ThermalGovernor(appContext) { post { if (isStreaming) recomputeFormat() } }

        val screen = ScreenCapturerAndroid(projectionData, projectionCallback)
        capturer = screen
        screen.initialize(textureHelper, appContext, frames)

        val (size, fps) = computeFormat()
        currentSize = size
        currentFps = fps
        screen.startCapture(size.width, size.height, fps) // obtains the MediaProjection
        source.adaptOutputFormat(size.width, size.height, fps)

        videoTrack = factory.createVideoTrack("screen", source)
        frames.start()
        isStreaming = true
        thermal?.start()
        registerDisplayListener()
        statsTask = executor.scheduleWithFixedDelay({ pollStats() }, 2, 2, TimeUnit.SECONDS)
        Log.i(TAG, "capture started $size@$fps, encoder=${encoders.hardwareH264Name ?: "software VP8"}")
        listener.onCaptureInfo(captureInfo(size, fps))
    }

    private fun captureInfo(size: CaptureSize, fps: Int) =
        CaptureInfo(size.width, size.height, fps, thermalLevel = thermal?.level?.ordinal ?: 0)

    private fun computeFormat(): Pair<CaptureSize, Int> {
        val (displaySize, refreshHz) = displayInfo()
        val display = contentSize ?: displaySize
        val encoders = encoderFactory
        val hardware = encoders?.isH264Offered == true
        val caps = if (hardware) encoderCaps else null
        val heat = thermal?.level ?: ThermalGovernor.Level.NORMAL
        val resolution = if (heat >= ThermalGovernor.Level.HOT && settings.resolution != ResolutionMode.P720) {
            ResolutionMode.P720
        } else {
            settings.resolution
        }
        val probe = encoderProbe
        val align = if (hardware && probe != null) {
            CaptureFormat.Alignment(probe.widthAlignment, probe.heightAlignment)
        } else {
            CaptureFormat.Alignment.EVEN
        }
        val (size, chosenFps) = CaptureFormat.choose(
            display = display,
            resolution = resolution,
            fpsMode = settings.fps,
            preferSmooth = settings.priority == Priority.SMOOTH,
            displayRefreshHz = refreshHz,
            hardwareEncoder = hardware,
            align = align,
        ) { w, h, fps ->
            caps == null || runCatching {
                if (fps <= 30) caps.isSizeSupported(w, h) else caps.areSizeAndRateSupported(w, h, fps.toDouble())
            }.getOrDefault(false)
        }
        val fps = when {
            heat >= ThermalGovernor.Level.CRITICAL -> minOf(chosenFps, 15)
            heat >= ThermalGovernor.Level.WARM -> minOf(chosenFps, 30)
            else -> chosenFps
        }
        var maxBps = CaptureFormat.maxBitrateBps(settings.bitrateMbps, size, fps)
        // Never ask the hardware encoder for more than it declares it can do.
        if (hardware) probe?.bitrateRange?.let { maxBps = minOf(maxBps, (it.last * 0.9).toInt()) }
        if (heat >= ThermalGovernor.Level.HOT) maxBps = (maxBps * 0.7).toInt()
        maxBps = maxOf(maxBps, CaptureFormat.MIN_BITRATE_BPS)
        sendProfile = SendProfile(
            maxBitrateBps = maxBps,
            startBitrateBps = CaptureFormat.startBitrateBps(maxBps),
            fps = fps,
            degradation = if (settings.priority == Priority.SMOOTH) {
                RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            } else {
                RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            },
            liteFps = LoadPolicy.liteFps(fps, heat),
            liteScale = LoadPolicy.liteScale(size.width, size.height),
        )
        return size to fps
    }

    private fun deviceHasHardware(): Boolean = encoderFactory?.hardwareH264Name != null

    /**
     * Hardware sessions the device is known to hold: a limit it demonstrated (stored after a
     * repeated refusal) or the count at its last refusal in this broadcast. Null while unknown —
     * then hardware is tried and a refusal is caught in [onPeerEncoder]. Vendor performance points
     * are a minimum guarantee, not a ceiling, so they are not used here.
     */
    private fun hardwareBudget(): Int? {
        val learned = EncoderProbe.learnedBudget(appContext, encoderProbe?.codecName)
        val session = sessionHardwareLimit?.takeIf { SystemClock.elapsedRealtime() < sessionHardwareLimitUntilMs }
        return listOfNotNull(learned, session).minOrNull()
    }

    private fun softwareBudget(): Int = LoadPolicy.softwareBudget(
        Runtime.getRuntime().availableProcessors(),
        deviceHasHardware(),
        thermal?.level ?: ThermalGovernor.Level.NORMAL,
    )

    private fun softwareSessions(): Int =
        if (deviceHasHardware()) peers.values.count { it.softwareEncoder } else peers.size

    /** Hardware encoders running plus hardware viewers still connecting (their encoder starts later). */
    /**
     * Hardware encoders taken or about to be taken. Every hardware viewer holds (or will hold) one;
     * counting live encoders plus connecting viewers counted a viewer whose encoder already started
     * twice and turned others away as busy. The live count also covers encoders of closing viewers.
     */
    private fun hardwareInUse(): Int = maxOf(
        TunedVideoEncoderFactory.getLiveHardwareEncoders(),
        peers.values.count { !it.softwareEncoder },
    )

    private fun admission(): LoadPolicy.Tier = LoadPolicy.admit(
        hardwareOffered = encoderFactory?.isH264Offered == true,
        hardwareBudget = hardwareBudget(),
        hardwareInUse = hardwareInUse(),
        softwareInUse = softwareSessions(),
        softwareBudget = softwareBudget(),
    )

    private fun loadLine(): String =
        "viewers=${peers.size} hw=${hardwareInUse()}/${hardwareBudget() ?: "?"} " +
            "sw=${softwareSessions()}/${softwareBudget()} thermal=${thermal?.level ?: ThermalGovernor.Level.NORMAL}"

    /** Re-splits the Wi-Fi between the viewers and applies the caps to every sender. */
    private fun rebalance() {
        // Viewers on the USB cable don't share the Wi-Fi.
        val overWifi = peers.values.filter { !it.viaUsb }
        val lite = overWifi.count { it.softwareEncoder }
        wifiMhz = WifiBand.currentMhz(appContext)
        val link = LoadPolicy.linkBudgetBps(wifiMhz)
        linkBps = link
        val next = LoadPolicy.shares(sendProfile.maxBitrateBps, link, overWifi.size - lite, lite)
        if (next != shares) {
            Log.i(DIAG_TAG, "bitrate split: ${overWifi.size} Wi-Fi viewers ($lite lite), link ${link / 1_000_000} Mbps -> " +
                "${next.fullBps / 1000} / ${next.liteBps / 1000} kbps")
        }
        shares = next
        peers.values.forEach { it.applySendParameters() }
    }

    private fun recomputeFormat() {
        val (size, fps) = computeFormat()
        if (size != currentSize || fps != currentFps) {
            Log.i(TAG, "capture format $currentSize@$currentFps -> $size@$fps")
            currentSize = size
            currentFps = fps
            capturer?.changeCaptureFormat(size.width, size.height, fps)
            videoSource?.adaptOutputFormat(size.width, size.height, fps)
            refresher?.requestFrame()
        }
        listener.onCaptureInfo(captureInfo(size, fps)) // also carries the thermal level
        rebalance()
    }

    private fun shutdownInternal() {
        if (released) return
        released = true
        isStreaming = false
        statsTask?.cancel(false)
        h264RetryTask?.cancel(false)
        thermal?.stop()
        thermal = null
        unregisterDisplayListener()
        peers.values.toList().forEach { it.closeNow() }
        peers.clear()
        refresher?.stop()
        capturer?.let {
            runCatching { it.stopCapture() }.onFailure { e -> Log.w(TAG, "stopCapture", e) }
            it.dispose()
        }
        capturer = null
        if (::videoTrack.isInitialized) runCatching { videoTrack.dispose() }
        videoSource?.let { runCatching { it.dispose() } }
        videoSource = null
        helper?.let { runCatching { it.dispose() } }
        helper = null
        if (::factory.isInitialized) runCatching { factory.dispose() }
        eglBase?.release()
        eglBase = null
        Log.i(TAG, "engine released")
    }

    private fun pollStats() {
        // Runs on a fixed-delay schedule: an escaping exception would silently cancel it.
        peers.values.forEach { peer ->
            try {
                peer.pollStats()
            } catch (t: Throwable) {
                Log.w(TAG, "stats poll failed for ${peer.viewerId}", t)
            }
        }
        try {
            reportLoad()
        } catch (t: Throwable) {
            Log.w(TAG, "load report failed", t)
        }
    }

    private fun turnAway(peer: ViewerPeer) {
        turnedAwayAt[peer.remoteAddress] = SystemClock.elapsedRealtime()
        peer.rejectBusy()
        reportLoad()
    }

    private fun reportLoad() {
        if (!isStreaming) return
        val now = SystemClock.elapsedRealtime()
        turnedAwayAt.values.removeAll { now - it > TURNED_AWAY_WINDOW_MS }
        listener.onLoad(
            LoadInfo(
                viewers = peers.size,
                liteViewers = peers.values.count { it.softwareEncoder },
                turnedAway = turnedAwayAt.size,
                linkKbps = linkBps / 1000,
                perViewerCapKbps = shares.fullBps / 1000,
                hardwareEncoders = TunedVideoEncoderFactory.getLiveHardwareEncoders(),
                thermalLevel = thermal?.level?.ordinal ?: 0,
                summary = loadLine() + " link=${linkBps / 1000}kbps cap=${shares.fullBps / 1000}kbps" +
                    " capture=$currentSize@$currentFps wifi=${wifiMhz ?: "-"}MHz",
                wifiMhz = wifiMhz,
            ),
        )
    }

    // ---- callbacks used by ViewerPeer (engine thread) ----

    internal fun rtcConfiguration(): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            keyType = PeerConnection.KeyType.ECDSA
            // DSCP marks map to the Wi-Fi WMM "video" access category: less queuing on busy Wi-Fi.
            enableDscp = true
            // Hardware encoder: CPU-overuse adaptation would only lower resolution for no reason.
            enableCpuOveruseDetection = false
            cryptoOptions = CryptoOptions.builder()
                .setEnableGcmCryptoSuites(true)
                .setEnableAes128Sha1_80CryptoCipher(true)
                .createCryptoOptions()
        }

    /**
     * H.264 first (when the hardware encoder is usable), VP8 fallback, plus RTX for retransmissions.
     * [preferSoftware]: VP8 first for a viewer beyond the hardware session budget.
     */
    /**
     * The device's local addresses changed (a cable plugged or pulled, Wi-Fi switched): viewers
     * whose WebSocket came over an address that is gone are closed now; their pages reconnect
     * over another path instead of waiting for connection timeouts.
     */
    fun onLocalAddressesChanged(ips: Set<String>) = post {
        val gone = peers.values.filter { it.localAddress != null && it.localAddress !in ips }
        gone.forEach {
            Log.i(DIAG_TAG, "${it.viewerId}: path ${it.localAddress} is gone, closing")
            it.closeNow()
        }
    }

    internal fun applyCodecPreferences(transceiver: RtpTransceiver, preferSoftware: Boolean) {
        val codecs = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        // H.265 first where the hardware has it: browsers that decode it (Chrome/Edge 136+ with a
        // hardware decoder, Safari 18+) take it, the rest answer H.264, the oldest VP8.
        val h265 = if (encoderFactory?.isH265Offered == true) codecs.filter { it.name.equals("H265", true) } else emptyList()
        val h264 = if (encoderFactory?.isH264Offered == true) codecs.filter { it.name.equals("H264", true) } else emptyList()
        val vp8 = codecs.filter { it.name.equals("VP8", true) }
        val rtx = codecs.filter { it.name.equals("rtx", true) }
        // FlexFEC is only negotiated when listed here (and advertised by the field trial); browsers
        // that don't receive it simply leave it out of the answer.
        val fec = if (flexFec) codecs.filter { it.name.equals("flexfec-03", true) } else emptyList()
        val ordered = (if (preferSoftware) vp8 + h264 else h265 + h264 + vp8) + rtx + fec
        if (ordered.isEmpty()) return
        val result = transceiver.setCodecPreferences(ordered)
        Log.d(TAG, "codec preferences ${ordered.map { it.name }}: $result")
    }

    internal fun onPeerConnectionState(viewerId: String, connected: Boolean) =
        listener.onViewerConnection(viewerId, connected)

    internal fun onViewerConnected() {
        // Make sure a freshly started encoder gets a frame even if the screen is static.
        refresher?.requestFrame()
        executor.schedule({ refresher?.requestFrame() }, 300, TimeUnit.MILLISECONDS)
    }

    internal fun onPeerClosed(peer: ViewerPeer) {
        if (peers.remove(peer.viewerId) != null && isStreaming) rebalance()
        listener.onViewerConnection(peer.viewerId, false)
    }

    /** Start bitrate for [peer]'s (re)negotiation: gentle when others are already watching. */
    internal fun startBitrateFor(peer: ViewerPeer, maxBps: Int): Int =
        LoadPolicy.startBps(maxBps, peers.values.count { it !== peer && it.isConnected })

    /**
     * Codec and encoder seen in [peer]'s stats. On a device with hardware H.264, VP8 on a viewer
     * that was given hardware means the encoder refused it (session limit) and libwebrtc switched
     * it to VP8 at full size, which would eat the CPU: make it a lite stream, or turn the viewer
     * away when the CPU budget is used up.
     */
    internal fun onPeerEncoder(peer: ViewerPeer, codec: String?, hardware: Boolean?) {
        if (peers[peer.viewerId] !== peer || peer.softwareEncoder || !deviceHasHardware()) return
        when {
            codec.equals("H264", ignoreCase = true) || codec.equals("H265", ignoreCase = true) ->
                if (hardware == true) peer.hardwareConfirmed = true
            codec.equals("VP8", ignoreCase = true) -> {
                if (peers.values.count { it.softwareEncoder } < softwareBudget()) {
                    Log.w(DIAG_TAG, "${peer.viewerId}: hardware encoder unavailable, lite VP8: ${loadLine()}")
                    peer.softwareEncoder = true
                    rebalance()
                } else {
                    Log.w(DIAG_TAG, "${peer.viewerId}: hardware encoder unavailable, turned away: ${loadLine()}")
                    turnAway(peer)
                }
            }
        }
    }

    /** After a hardware refusal: find the viewer it hit quickly instead of waiting for the 2 s poll. */
    private fun pollUnconfirmedSoon() {
        for (delayMs in longArrayOf(300, 1_000)) {
            executor.schedule({
                if (released) return@schedule
                peers.values.filter { !it.softwareEncoder && !it.hardwareConfirmed }.forEach { peer ->
                    runCatching { peer.pollStats() }.onFailure { Log.w(TAG, "stats poll failed", it) }
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    internal fun onSenderStats(viewerId: String, stats: SenderStats) = listener.onSenderStats(viewerId, stats)

    internal fun onViewerReport(viewerId: String, stats: ViewerStats) = listener.onViewerReport(viewerId, stats)

    internal fun isHardwareImplementation(name: String): Boolean {
        val f = encoderFactory ?: return false
        return listOfNotNull(f.hardwareH264Name, f.hardwareH265Name).any { name.contains(it, ignoreCase = true) }
    }

    // ---- display geometry ----

    private fun displayInfo(): Pair<CaptureSize, Float> {
        val dm = appContext.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val size = try {
            val ctx = windowContext ?: appContext.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                .also { windowContext = it }
            val bounds = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            CaptureSize(bounds.width(), bounds.height())
        } catch (e: RuntimeException) {
            Log.w(TAG, "window metrics unavailable, using display size", e)
            val p = Point()
            @Suppress("DEPRECATION") display.getRealSize(p)
            CaptureSize(p.x, p.y)
        }
        return size to display.refreshRate
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            // On Android 14+ onCapturedContentResize is authoritative (also covers single-app capture).
            post { if (isStreaming && contentSize == null) recomputeFormat() }
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    private fun registerDisplayListener() {
        appContext.getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, mainHandler)
    }

    private fun unregisterDisplayListener() {
        runCatching { appContext.getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped")
            post {
                if (!released && isStreaming) {
                    isStreaming = false
                    listener.onCaptureStopped(null)
                }
            }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            post {
                contentSize = CaptureSize(width, height)
                if (isStreaming) recomputeFormat()
            }
        }
    }

    private val encoderListener = object : TunedVideoEncoderFactory.Listener {
        override fun onEncoderCreated(codecName: String, hardware: Boolean) {
            Log.i(TAG, "encoder created: $codecName (hardware=$hardware)")
        }

        override fun onEncoderFailed(codecName: String, reason: String, otherEncodersActive: Boolean) {
            if (otherEncodersActive) {
                // The device's limit of concurrent hardware encoders: this viewer falls back to VP8 by
                // itself (libwebrtc encoder switch), everybody else keeps H.264, and later viewers
                // beyond this number start on VP8 right away (remembered per device).
                val running = TunedVideoEncoderFactory.getLiveHardwareEncoders()
                Log.w(TAG, "hardware encoder $codecName failed with $running others running ($reason) — keeping H.264")
                EncoderProbe.learnBudget(appContext, codecName, running)
                post {
                    // 0: the others were released meanwhile, so this says nothing about the limit.
                    if (running > 0) {
                        sessionHardwareLimit = running
                        sessionHardwareLimitUntilMs = SystemClock.elapsedRealtime() + SESSION_LIMIT_MS
                    }
                    pollUnconfirmedSoon()
                }
                return
            }
            if (codecName == encoderFactory?.hardwareH265Name) {
                // libwebrtc switches the session to the next negotiated codec (H.264) by itself;
                // new viewers are not offered H.265 again during this broadcast.
                Log.e(TAG, "hardware HEVC encoder $codecName failed: $reason — H.264 from now on")
                post { encoderFactory?.setH265Disabled(true) }
                return
            }
            Log.e(TAG, "hardware encoder $codecName failed: $reason — VP8 for new sessions, retry in ${H264_RETRY_S}s")
            // libwebrtc switches the running session to the next negotiated codec (VP8) by itself;
            // stop offering H.264 for a while so later viewers start directly on the working path.
            post {
                encoderFactory?.setH264Disabled(true)
                if (isStreaming) recomputeFormat()
                h264RetryTask?.cancel(false)
                h264RetryTask = executor.schedule({
                    if (released) return@schedule
                    Log.i(TAG, "offering hardware H.264 to new viewers again")
                    encoderFactory?.setH264Disabled(false)
                    if (isStreaming) recomputeFormat()
                }, H264_RETRY_S, TimeUnit.SECONDS)
            }
        }
    }

    companion object {
        private const val TAG = "LocalCast/Engine"
        private const val DIAG_TAG = "LocalCast/Diag"
        private const val H264_RETRY_S = 30L

        /** A refusal may be transient (another app held an encoder): try hardware again after this. */
        private const val SESSION_LIMIT_MS = 60_000L

        /** A turned-away device counts in the "could not join" figure for this long (it retries every 10 s). */
        private const val TURNED_AWAY_WINDOW_MS = 60_000L

        /**
         * libwebrtc field trials (names/params verified in the bundled m150 .so):
         * - ProbingScreenshareBwe is default-on for screencast sources with fixed settings
         *   "1.0,2875,80,40,-60,3": pacer at exactly 1.0× the bandwidth estimate and a queue of up to
         *   2.875 s. Hardware encoders overshoot their target on motion, so on a phone the pacer queued
         *   1–2 s of video. Only "Disabled" turns it off; then Video-Pacing applies.
         * - Video-Pacing: the pacer may burst at 2.5× the estimate and never holds more than 100 ms.
         * - ForceSendPlayoutDelay: tells the browser min=0/max=100 ms playout delay, i.e. render frames
         *   as soon as they are decoded instead of growing the jitter buffer after a burst.
         */
        private const val BASE_FIELD_TRIALS =
            "WebRTC-ProbingScreenshareBwe/Disabled/" +
                "WebRTC-Video-Pacing/factor:2.5,max_delay:100ms/" +
                "WebRTC-ForceSendPlayoutDelay/min_ms:0,max_ms:100/"

        /**
         * Bandwidth-estimator trials, a bit mask for A/B on a device (`debug.localcast.bwe_trials`).
         * Off by default: not yet measured on a quiet network (the test Wi-Fi was saturated).
         */
        private const val TRIAL_ALR_PROBING = 1
        private const val TRIAL_TRENDLINE = 2
        private const val TRIAL_RAPID_RECOVERY = 4
        private const val DEFAULT_BWE_TRIALS = 0

        /** Defaults of the A/B switches above; decided by measurements on a device (see commits). */
        private const val DEFAULT_HEVC = 1
        private const val DEFAULT_FEC = 0

        /**
         * - ALR probing: while the screen is static (application-limited) probe at 2x the estimate
         *   every 2 s, so an estimate dropped by a Wi-Fi delay spike climbs back within seconds.
         * - Trendline: sort packets by arrival and cap the delay slope by the first/last packets,
         *   so Wi-Fi aggregation bursts are not read as a growing queue.
         * - Rapid recovery: allow the estimate to rise faster after a drop.
         */
        private fun fieldTrials(mask: Int): String = BASE_FIELD_TRIALS +
            (if (mask and TRIAL_ALR_PROBING != 0) "WebRTC-Bwe-AlrProbing/Enabled/" +
                "WebRTC-Bwe-ProbingConfiguration/alr_interval:2s,alr_scale:2/" else "") +
            (if (mask and TRIAL_TRENDLINE != 0) "WebRTC-Bwe-TrendlineEstimatorSettings/sort:true,cap:true/" else "") +
            (if (mask and TRIAL_RAPID_RECOVERY != 0) "WebRTC-BweRapidRecoveryExperiment/Enabled/" else "")
        @Volatile private var webRtcInitialized = false

        private fun ensureWebRtcInitialized(context: Context) {
            if (webRtcInitialized) return
            synchronized(this) {
                if (webRtcInitialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions(),
                )
                Logging.enableLogToDebugOutput(Logging.Severity.LS_WARNING)
                webRtcInitialized = true
            }
        }
    }
}
