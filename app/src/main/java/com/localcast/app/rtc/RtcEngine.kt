package com.localcast.app.rtc

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.localcast.app.server.ServerMessage
import com.localcast.app.server.ViewerStats
import com.localcast.app.service.CaptureInfo
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
    }

    /** What every sender is configured with; recomputed whenever settings or geometry change. */
    data class SendProfile(
        val maxBitrateBps: Int,
        val startBitrateBps: Int,
        val fps: Int,
        val degradation: RtpParameters.DegradationPreference,
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

    @Volatile override var isStreaming: Boolean = false
        private set

    private var eglBase: EglBase? = null
    private var encoderFactory: TunedVideoEncoderFactory? = null
    private var encoderCaps: MediaCodecInfo.VideoCapabilities? = null
    private var encoderProbe: EncoderCaps? = null
    private var thermal: ThermalGovernor? = null
    private var h264RetryTask: ScheduledFuture<*>? = null
    private var helper: SurfaceTextureHelper? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var refresher: FrameRefresher? = null
    private var windowContext: Context? = null
    private val peers = LinkedHashMap<String, ViewerPeer>()
    private var settings = StreamSettings()
    private var contentSize: CaptureSize? = null
    private var currentSize: CaptureSize? = null
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

    fun updateSettings(newSettings: StreamSettings) = post {
        settings = newSettings
        if (isStreaming) recomputeFormat()
    }

    override fun createViewer(viewerId: String, remoteAddress: String, send: (ServerMessage) -> Unit): ViewerSession? {
        if (!isStreaming) return null
        val peer = ViewerPeer(viewerId, remoteAddress, this, send)
        post {
            if (!isStreaming) {
                peer.closeNow()
                return@post
            }
            peer.softwareEncoder = hardwareSessionsExhausted()
            if (peer.softwareEncoder) Log.i(TAG, "$viewerId: hardware encoder budget used up — starting on VP8")
            peers[viewerId] = peer
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
        val encoders = TunedVideoEncoderFactory(egl.eglBaseContext, encoderListener, guard).also { encoderFactory = it }
        val probe = encoders.hardwareH264Name?.let(EncoderProbe::probe)
        encoderProbe = probe
        encoderCaps = probe?.videoCaps
        probe?.let { Log.i(DIAG_TAG, it.logLine(EncoderProbe.learnedBudget(appContext, it.codecName))) }

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
            .setFieldTrials(FIELD_TRIALS)
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
        )
        return size to fps
    }

    /**
     * True when a new viewer should start on software VP8 because the device's concurrent hardware
     * H.264 sessions are used up. Only a limit the device itself demonstrated counts (vendor
     * performance points are a minimum guarantee, not a ceiling); without one we keep trying
     * hardware and a repeated failure teaches the limit. Compared with the encoders actually running
     * plus hardware viewers that are still connecting (their encoder starts after negotiation).
     */
    private fun hardwareSessionsExhausted(): Boolean {
        if (encoderFactory?.isH264Offered != true) return false
        val budget = EncoderProbe.learnedBudget(appContext, encoderProbe?.codecName) ?: return false
        val starting = peers.values.count { !it.softwareEncoder && !it.isConnected }
        return TunedVideoEncoderFactory.getLiveHardwareEncoders() + starting >= budget
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
        peers.values.forEach { it.applySendParameters() }
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
    internal fun applyCodecPreferences(transceiver: RtpTransceiver, preferSoftware: Boolean) {
        val codecs = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        val h264 = if (encoderFactory?.isH264Offered == true) codecs.filter { it.name.equals("H264", true) } else emptyList()
        val vp8 = codecs.filter { it.name.equals("VP8", true) }
        val rtx = codecs.filter { it.name.equals("rtx", true) }
        val ordered = (if (preferSoftware) vp8 + h264 else h264 + vp8) + rtx
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
        peers.remove(peer.viewerId)
        listener.onViewerConnection(peer.viewerId, false)
    }

    internal fun onSenderStats(viewerId: String, stats: SenderStats) = listener.onSenderStats(viewerId, stats)

    internal fun onViewerReport(viewerId: String, stats: ViewerStats) = listener.onViewerReport(viewerId, stats)

    internal fun isHardwareImplementation(name: String): Boolean {
        val hw = encoderFactory?.hardwareH264Name ?: return false
        return name.contains(hw, ignoreCase = true)
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
                Log.w(TAG, "hardware encoder $codecName failed with others running ($reason) — keeping H.264")
                EncoderProbe.learnBudget(appContext, codecName, TunedVideoEncoderFactory.getLiveHardwareEncoders())
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
        private const val FIELD_TRIALS =
            "WebRTC-ProbingScreenshareBwe/Disabled/" +
                "WebRTC-Video-Pacing/factor:2.5,max_delay:100ms/" +
                "WebRTC-ForceSendPlayoutDelay/min_ms:0,max_ms:100/"
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
