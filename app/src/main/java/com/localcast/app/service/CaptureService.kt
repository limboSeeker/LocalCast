package com.localcast.app.service

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.localcast.app.LocalCastApp
import com.localcast.app.R
import com.localcast.app.rtc.RtcEngine
import com.localcast.app.server.HttpServer
import com.localcast.app.server.NetAddresses
import com.localcast.app.server.PinGuard
import com.localcast.app.server.ServerInfo
import com.localcast.app.server.ServerMessage
import com.localcast.app.server.Signaling
import com.localcast.app.server.ViewerStats
import com.localcast.app.settings.StreamSettings
import com.localcast.app.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service (type mediaProjection) that owns the broadcast: WebRTC engine, HTTP/WS server,
 * PIN guard and the Wi-Fi/CPU locks. All state for the UI goes through [CastStateStore].
 */
class CaptureService : LifecycleService() {

    private val pinGuard: PinGuard get() = (application as LocalCastApp).pinGuard
    private var engine: RtcEngine? = null
    private var server: HttpServer? = null
    private var signaling: Signaling? = null
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()
    private var wakeLock: PowerManager.WakeLock? = null
    private val jobs = mutableListOf<Job>()
    private var startJob: Job? = null
    @Volatile private var pinRequired = true
    @Volatile private var pauseOnLock = true
    private var screenReceiverRegistered = false
    private var stopping = false
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            CastController.ACTION_START -> handleStart(intent)
            CastController.ACTION_STOP -> stopCast(error = null)
            CastController.ACTION_REGENERATE_PIN -> regeneratePin()
            CastController.ACTION_PAUSE -> setPaused(PauseReason.USER)
            CastController.ACTION_RESUME -> setPaused(null)
            else -> if (!started) stopSelf() // restarted without a projection token: nothing to do
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (started) return
        val data = IntentCompat.getParcelableExtra(intent, CastController.EXTRA_RESULT_DATA, Intent::class.java)
        if (data == null) {
            CastStateStore.update { CastState(phase = CastPhase.ERROR, error = getString(R.string.error_projection_denied)) }
            stopSelf()
            return
        }
        started = true
        createChannel()
        try {
            // Android 14+: must be in the foreground (type mediaProjection) BEFORE getMediaProjection().
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "startForeground failed", e)
            started = false
            CastStateStore.update { CastState(phase = CastPhase.ERROR, error = getString(R.string.error_capture, e.message ?: "")) }
            stopSelf()
            return
        }

        CastStateStore.update { CastState(phase = CastPhase.STARTING, pin = pinGuard.pin) }
        startJob = lifecycleScope.launch {
            val app = application as LocalCastApp
            val settings = app.settings.current()
            pinRequired = settings.requirePin
            pauseOnLock = settings.pauseOnLock
            acquireLocks()
            registerScreenReceiver()

            val eng = RtcEngine(this@CaptureService, engineListener)
            engine = eng
            eng.start(data, settings)

            val sig = Signaling(pinGuard, eng, { pinRequired }, signalingListener)
            signaling = sig
            val srv = HttpServer(assets, sig) {
                ServerInfo(name = "LocalCast · ${Build.MODEL}", pinRequired = pinRequired, streaming = eng.isStreaming)
            }
            server = srv
            val port = try {
                withContext(Dispatchers.IO) { srv.start(settings.port) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "server start failed", e)
                stopCast(getString(R.string.error_server_start, settings.port, settings.port + 9))
                return@launch
            }
            if (stopping) return@launch // stop raced with start: teardown stops the server
            val addresses = withContext(Dispatchers.IO) { NetAddresses.current() }
            if (stopping) return@launch
            CastStateStore.update {
                it.copy(port = port, addresses = addresses, pin = pinGuard.pin, pinRequired = pinRequired)
            }
            updateNotification()
            jobs += launch { observeSettings(eng) }
            jobs += launch { pollAddresses() }
        }
    }

    private suspend fun observeSettings(eng: RtcEngine) {
        (application as LocalCastApp).settings.settings.collect { s: StreamSettings ->
            val pinTurnedOn = s.requirePin && !pinRequired
            pinRequired = s.requirePin
            pauseOnLock = s.pauseOnLock
            if (pinTurnedOn) {
                // Viewers admitted while the PIN was off must not keep (or regain) access.
                pinGuard.revokeAll()
                signaling?.disconnectAll("pin_changed")
            }
            eng.updateSettings(s)
            CastStateStore.update { it.copy(pinRequired = s.requirePin) }
            updateNotification() // the "New PIN" action depends on requirePin
        }
    }

    /** Hotspot interfaces do not show up as ConnectivityManager networks, so poll interfaces. */
    private suspend fun pollAddresses() {
        while (lifecycleScope.isActive) {
            delay(3_000)
            val addresses = withContext(Dispatchers.IO) { NetAddresses.current() }
            if (addresses != CastStateStore.state.value.addresses) {
                CastStateStore.update { it.copy(addresses = addresses) }
                updateNotification()
            }
        }
    }

    private fun regeneratePin() {
        if (!started || stopping) return
        val pin = pinGuard.regeneratePin() // also revokes every token
        CastStateStore.update { it.copy(pin = pin) }
        updateNotification()
        // Kick current viewers: they reconnect with a now-invalid token and get the PIN form.
        lifecycleScope.launch { signaling?.disconnectAll("pin_changed") }
    }

    /**
     * [reason] null resumes. A pause by the user is only lifted by the user; a pause by the lock
     * screen is lifted on unlock (and never overrides a user pause).
     */
    private fun setPaused(reason: PauseReason?) {
        if (!started || stopping) return
        val current = CastStateStore.state.value.pause
        if (reason == current) return
        if (reason == PauseReason.LOCKED && current == PauseReason.USER) return
        engine?.setPaused(reason != null)
        CastStateStore.update { it.copy(pause = reason) }
        broadcastStreamState()
        updateNotification()
    }

    /** Tells every viewer the stream geometry and whether it is paused. */
    private fun broadcastStreamState() {
        val s = CastStateStore.state.value
        val c = s.capture ?: return
        signaling?.broadcast(ServerMessage.State(streaming = !s.isPaused, width = c.width, height = c.height, fps = c.fps))
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
            when (intent.action) {
                // The lock screen shows notifications: pause before the screen comes back on.
                Intent.ACTION_SCREEN_OFF -> if (pauseOnLock) setPaused(PauseReason.LOCKED)
                Intent.ACTION_SCREEN_ON -> if (!locked) resumeAfterLock() // no keyguard (lock "None")
                Intent.ACTION_USER_PRESENT -> resumeAfterLock()
            }
        }
    }

    private fun resumeAfterLock() {
        if (CastStateStore.state.value.pause == PauseReason.LOCKED) setPaused(null)
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // System broadcasts still arrive with NOT_EXPORTED.
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        runCatching { unregisterReceiver(screenReceiver) }
        screenReceiverRegistered = false
    }

    private fun stopCast(error: String?) {
        if (stopping) return
        stopping = true
        if (!started) {
            stopSelf()
            return
        }
        CastStateStore.update { it.copy(phase = CastPhase.STOPPING) }
        lifecycleScope.launch { withContext(NonCancellable) { teardown(error) } }
    }

    private suspend fun teardown(error: String?) {
        run {
            startJob?.cancelAndJoin() // a start still in flight must not bind the port after we stop
            jobs.forEach { it.cancel() }
            withTimeoutOrNull(1_500) { signaling?.disconnectAll("stopped") }
            withContext(Dispatchers.IO) {
                runCatching { server?.stop() }
                runCatching { engine?.release() }
            }
            server = null
            engine = null
            signaling = null
            releaseLocks()
            unregisterScreenReceiver()
            CastStateStore.update {
                CastState(phase = if (error != null) CastPhase.ERROR else CastPhase.IDLE, error = error)
            }
            ServiceCompat.stopForeground(this@CaptureService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** The app language can change while casting (Android 13+): redraw the channel name and notification. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!started || stopping) return
        createChannel()
        updateNotification()
    }

    override fun onDestroy() {
        // Normal path already cleaned up in stopCast(); this covers the service being torn down early.
        if (engine != null || server != null) {
            runCatching { server?.stop() }
            runCatching { engine?.release() }
            CastStateStore.update { CastState() }
        }
        releaseLocks() // idempotent; also drops the activity callbacks that reference this service
        unregisterScreenReceiver()
        super.onDestroy()
    }

    // ---- callbacks ----

    private val engineListener = object : RtcEngine.Listener {
        override fun onCaptureInfo(info: CaptureInfo) {
            CastStateStore.update {
                if (it.phase == CastPhase.STARTING || it.phase == CastPhase.LIVE) {
                    it.copy(phase = CastPhase.LIVE, capture = info)
                } else {
                    it
                }
            }
            broadcastStreamState()
            lifecycleScope.launch { updateNotification() }
        }

        override fun onCaptureStopped(error: String?) {
            lifecycleScope.launch { stopCast(error?.let { getString(R.string.error_capture, it) }) }
        }

        override fun onViewerConnection(viewerId: String, connected: Boolean) = updateViewer(viewerId) {
            it.copy(connected = connected)
        }

        override fun onSenderStats(viewerId: String, stats: SenderStats) = updateViewer(viewerId) {
            it.copy(sender = stats)
        }

        override fun onViewerReport(viewerId: String, stats: ViewerStats) = updateViewer(viewerId) {
            it.copy(viewer = stats)
        }

        override fun onLoad(load: LoadInfo) {
            CastStateStore.update { it.copy(load = load, health = HealthPolicy.evaluate(load, it.viewers)) }
            Diagnostics.remember(this@CaptureService, load = load.summary)
        }

        override fun onEncoderCaps(line: String) {
            CastStateStore.update { it.copy(encoderCaps = line) }
            Diagnostics.remember(this@CaptureService, encoderCaps = line)
        }
    }

    private val signalingListener = object : Signaling.Listener {
        override fun onViewerJoined(viewerId: String, remoteIp: String, userAgent: String?) {
            CastStateStore.update { s ->
                s.copy(viewers = s.viewers.filterNot { it.id == viewerId } +
                    ViewerInfo(id = viewerId, remoteAddress = remoteIp, userAgent = userAgent, connected = false))
            }
            // A viewer joining during a pause must show the "paused" card, not a black picture.
            if (CastStateStore.state.value.isPaused) broadcastStreamState()
            lifecycleScope.launch { updateNotification() }
        }

        override fun onViewerLeft(viewerId: String) {
            CastStateStore.update { s -> s.copy(viewers = s.viewers.filterNot { it.id == viewerId }) }
            lifecycleScope.launch { updateNotification() }
        }
    }

    private fun updateViewer(viewerId: String, transform: (ViewerInfo) -> ViewerInfo) {
        CastStateStore.update { s ->
            s.copy(viewers = s.viewers.map { if (it.id == viewerId) transform(it) else it })
        }
    }

    // ---- locks ----

    private fun acquireLocks() {
        acquireWifiLocks()
        // Keeps sockets alive if the screen turns off briefly; capped as a safety net.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalCast:cast")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        application.registerActivityLifecycleCallbacks(activityCallbacks)
    }

    private fun acquireWifiLocks() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val modes = listOf(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WifiManager.WIFI_MODE_FULL_HIGH_PERF)
        for (mode in modes) {
            runCatching {
                wifi.createWifiLock(mode, "LocalCast:wifi$mode").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onSuccess { wifiLocks += it }.onFailure { Log.w(TAG, "wifi lock $mode", it) }
        }
    }

    private fun releaseWifiLocks() {
        wifiLocks.forEach { runCatching { if (it.isHeld) it.release() } }
        wifiLocks.clear()
    }

    private fun releaseLocks() {
        runCatching { application.unregisterActivityLifecycleCallbacks(activityCallbacks) }
        releaseWifiLocks()
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    /**
     * Wi-Fi low-latency mode (no power save) is granted only while the app is in the foreground, and
     * that is evaluated when the lock is acquired. The locks are first taken while the system
     * screen-capture dialog is still on top, so they could stay inactive (`dumpsys wifi`:
     * isFg=false). Re-taking them whenever our activity is shown makes the mode actually apply while
     * the user is in the app. (Outside the app the platform turns it off by design.)
     */
    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (wifiLocks.isEmpty()) return
            // New locks first, then drop the old ones: Wi-Fi never leaves low-latency mode between.
            val old = wifiLocks.toList()
            wifiLocks.clear()
            acquireWifiLocks()
            old.forEach { runCatching { if (it.isHeld) it.release() } }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    // ---- notification ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
            .apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
        nm.createNotificationChannel(channel)
    }

    /** Wall-clock time the broadcast went live: the notification chronometer counts from it. */
    private var liveSinceMs = 0L

    private fun buildNotification(): Notification {
        val state = CastStateStore.state.value
        val live = state.phase == CastPhase.LIVE
        if (live && liveSinceMs == 0L) liveSinceMs = System.currentTimeMillis()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(CastController.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when {
            live && state.isPaused -> R.string.notif_title_paused
            live -> R.string.notif_title_live
            else -> R.string.notif_title_starting
        }
        val url = state.primaryUrl?.removeSuffix("/")
        val text = when {
            url != null -> resources.getQuantityString(R.plurals.notif_viewers, state.viewers.size, state.viewers.size, url)
            state.port != null -> getString(R.string.notif_text_no_network)
            else -> null
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setColor(getColor(R.color.notif_accent))
            .setContentTitle(getString(title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stop, getString(R.string.notif_action_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (live) {
            val resume = state.isPaused
            val toggle = PendingIntent.getService(
                this, 3,
                Intent(this, CaptureService::class.java)
                    .setAction(if (resume) CastController.ACTION_RESUME else CastController.ACTION_PAUSE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(
                if (resume) R.drawable.ic_play else R.drawable.ic_pause,
                getString(if (resume) R.string.notif_action_resume else R.string.notif_action_pause),
                toggle,
            )
        }
        if (state.pinRequired && state.pin != null) {
            val newPin = PendingIntent.getService(
                this, 2,
                Intent(this, CaptureService::class.java).setAction(CastController.ACTION_REGENERATE_PIN),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(R.drawable.ic_refresh, getString(R.string.notif_action_new_pin), newPin)
        }
        if (live) {
            builder.setWhen(liveSinceMs).setShowWhen(true).setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
        }
        return builder.build()
    }

    private fun updateNotification() {
        if (!started || stopping) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private companion object {
        const val TAG = "LocalCast/Service"
        const val CHANNEL_ID = "cast"
        const val NOTIFICATION_ID = 1
        const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
    }
}
