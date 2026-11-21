package com.localcast.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
    private var stopping = false
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            CastController.ACTION_START -> handleStart(intent)
            CastController.ACTION_STOP -> stopCast(error = null)
            CastController.ACTION_REGENERATE_PIN -> regeneratePin()
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
            acquireLocks()

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
            if (pinTurnedOn) {
                // Viewers admitted while the PIN was off must not keep (or regain) access.
                pinGuard.revokeAll()
                signaling?.disconnectAll("pin_changed")
            }
            eng.updateSettings(s)
            CastStateStore.update { it.copy(pinRequired = s.requirePin) }
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
        // Kick current viewers: they reconnect with a now-invalid token and get the PIN form.
        lifecycleScope.launch { signaling?.disconnectAll("pin_changed") }
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
            CastStateStore.update {
                CastState(phase = if (error != null) CastPhase.ERROR else CastPhase.IDLE, error = error)
            }
            ServiceCompat.stopForeground(this@CaptureService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Normal path already cleaned up in stopCast(); this covers the service being torn down early.
        if (engine != null || server != null) {
            runCatching { server?.stop() }
            runCatching { engine?.release() }
            releaseLocks()
            CastStateStore.update { CastState() }
        }
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
            signaling?.broadcast(ServerMessage.State(streaming = true, width = info.width, height = info.height, fps = info.fps))
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
    }

    private val signalingListener = object : Signaling.Listener {
        override fun onViewerJoined(viewerId: String, remoteIp: String, userAgent: String?) {
            CastStateStore.update { s ->
                s.copy(viewers = s.viewers.filterNot { it.id == viewerId } +
                    ViewerInfo(id = viewerId, remoteAddress = remoteIp, userAgent = userAgent, connected = false))
            }
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
        // Keeps sockets alive if the screen turns off briefly; capped as a safety net.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalCast:cast")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    private fun releaseLocks() {
        wifiLocks.forEach { runCatching { if (it.isHeld) it.release() } }
        wifiLocks.clear()
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
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
        val title = if (live) R.string.notif_title_live else R.string.notif_title_starting
        val url = state.primaryUrl?.removeSuffix("/")
        val text = when {
            url != null -> getString(R.string.notif_text_viewers, state.viewers.size, url)
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
