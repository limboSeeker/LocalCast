package com.localcast.app.server

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import java.io.IOException
import java.net.URI
import java.net.BindException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Embedded Ktor (CIO) server: viewer page, `/api/info`, and the `/ws` signaling socket. */
class HttpServer(
    private val assets: AssetManager,
    private val signaling: Signaling,
    /** This device's own IPv4 addresses: a page loaded from one of them may open `/ws` on another (multipath). */
    private val ownIps: () -> Collection<String> = { NetAddresses.current().map { it.ip } },
    private val info: () -> ServerInfo,
) {
    private val lock = Any()
    private var engine: EmbeddedServer<*, *>? = null
    private var boundPort = 0

    /** Binds the first free port in `preferredPort until preferredPort + attempts`; returns it. */
    fun start(preferredPort: Int, attempts: Int = 10): Int = synchronized(lock) {
        check(engine == null) { "already started" }
        var last: Throwable? = null
        // Viewers keep the address (and the QR they scanned): right after our own server on this
        // port stopped it may still be closing, so wait for it. A port held by another app is not
        // waited for.
        val waitMs = if (recentlyStopped(preferredPort)) PREFERRED_PORT_WAIT_MS else 0L
        val first = waitForPort(preferredPort, waitMs, ::isPortFree)
        for (port in preferredPort until preferredPort + attempts) {
            // Ktor CIO binds asynchronously: a busy port surfaces as a BindException in a background
            // coroutine (which crashed the app) instead of from start(). Probe it first.
            if (!(if (port == preferredPort) first else isPortFree(port))) {
                last = BindException("port $port is busy")
                continue
            }
            val candidate = createEngine(port)
            try {
                candidate.start(wait = false)
                engine = candidate
                boundPort = port
                Log.i(TAG, "listening on :$port")
                return port
            } catch (t: Throwable) {
                runCatching { candidate.stop(0, 0) }
                if (!isBindFailure(t)) throw IOException("HTTP server failed to start on port $port", t)
                last = t
            }
        }
        throw IOException("No free port in $preferredPort..${preferredPort + attempts - 1}", last)
    }

    /**
     * Same address-reuse semantics as the CIO engine (reuseAddress = true): connections of the
     * previous broadcast left in TIME_WAIT (up to a minute) do not block the port, a listening
     * socket still does. A port taken between this probe and the bind can still fail late.
     */
    private fun isPortFree(port: Int): Boolean = try {
        java.net.ServerSocket().use {
            it.reuseAddress = true
            it.bind(java.net.InetSocketAddress(port))
        }
        true
    } catch (e: IOException) {
        false
    }

    fun stop() {
        val e = synchronized(lock) {
            // Marked before stopping: a start racing with this stop already waits for the port.
            if (engine != null) lastStop = boundPort to SystemClock.elapsedRealtime()
            engine.also { engine = null }
        } ?: return
        try {
            e.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        } catch (t: Throwable) {
            Log.w(TAG, "stop failed", t)
        }
        lastStop = lastStop.first to SystemClock.elapsedRealtime()
    }

    private fun createEngine(port: Int) = embeddedServer(
        CIO,
        configure = {
            connectors.add(
                EngineConnectorBuilder().apply {
                    this.port = port
                    host = "0.0.0.0"
                },
            )
            // Without it the port of a broadcast stopped seconds ago is busy (TIME_WAIT) and the
            // server moved to the next port: the address and QR viewers had no longer worked.
            reuseAddress = true
        },
    ) {
        install(WebSockets) {
            pingPeriod = 5.seconds
            timeout = 15.seconds
            maxFrameSize = 1024L * 1024L
        }
        routing {
            get("/") { respondIndex() }
            get("/index.html") { respondIndex() }
            get("/api/info") {
                call.respondText(
                    Protocol.json.encodeToString(ServerInfo.serializer(), info()),
                    ContentType.Application.Json,
                )
            }
            get("/health") { call.respondText("ok") }
            get("/favicon.ico") { call.respond(HttpStatusCode.NoContent) }
            webSocket("/ws") {
                // Browsers always send Origin for WebSocket handshakes. Reject other sites' pages
                // (cross-site WebSocket hijacking); non-browser clients without Origin are allowed.
                // A page served from another of this device's addresses (Wi-Fi page, USB socket) is ours too.
                val origin = call.request.headers[HttpHeaders.Origin]
                val host = call.request.headers[HttpHeaders.Host]
                if (origin != null && !originAllowed(origin, host) { runCatching { ownIps() }.getOrDefault(emptyList()) }) {
                    Log.w(TAG, "rejected WebSocket from foreign origin $origin (host $host)")
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin"))
                    return@webSocket
                }
                signaling.handle(this, call.request.origin.remoteAddress, plainIp(call.request.local.localAddress))
            }
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.respondIndex() {
        val bytes = try {
            withContext(Dispatchers.IO) { assets.open("viewer/index.html").use { it.readBytes() } }
        } catch (e: IOException) {
            Log.w(TAG, "viewer/index.html missing", e)
            call.respondText("Viewer page is not available", status = HttpStatusCode.ServiceUnavailable)
            return
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondBytes(bytes, ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }

    private fun isBindFailure(t: Throwable): Boolean {
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth++ < 10) {
            if (c is BindException) return true
            val m = c.message
            if (m != null && (m.contains("Address already in use", true) || m.contains("EADDRINUSE", true))) return true
            c = c.cause
        }
        return false
    }

    private companion object {
        const val TAG = "LocalCast/Http"
    }
}

internal const val PREFERRED_PORT_WAIT_MS = 3_000L

/**
 * Origin rule for `/ws` (cross-site WebSocket hijacking protection). Allowed: the same origin as [host] (the `Host`
 * header), or an `http` origin whose host is one of this device's own IPv4 addresses ([ownIps], resolved
 * on demand) on the same port as [host] — a page loaded from the Wi-Fi address may open the socket on the USB address
 * and vice versa. Everything else (other sites, other ports, names, https) is rejected.
 */
internal fun originAllowed(origin: String, host: String?, ownIps: () -> Collection<String>): Boolean {
    if (host == null) return false
    val o = runCatching { URI(origin) }.getOrNull() ?: return false
    val authority = o.rawAuthority ?: return false
    if (authority.equals(host, ignoreCase = true)) return true
    if (!o.scheme.equals("http", ignoreCase = true)) return false
    val originHost = o.host ?: return false
    val hostPort = runCatching { URI("http://$host").port }.getOrNull() ?: return false
    val originPort = if (o.port == -1) 80 else o.port
    if (originPort != (if (hostPort == -1) 80 else hostPort)) return false
    return originHost in ownIps()
}

/** Socket address as a plain IP: IPv6-mapped IPv4 (`::ffff:a.b.c.d`) unwrapped, brackets/zone dropped; null if unknown. */
internal fun plainIp(address: String?): String? {
    var a = address?.trim()?.removePrefix("/") ?: return null
    if (a.startsWith("[") && a.endsWith("]")) a = a.substring(1, a.length - 1)
    a = a.substringBefore('%')
    if (a.startsWith("::ffff:", ignoreCase = true) && a.count { it == '.' } == 3) a = a.substring(7)
    return a.takeIf { it.isNotEmpty() && !it.equals("localhost", ignoreCase = true) }
}

/** Port and time (elapsedRealtime) at which the last server of this process stopped. */
@Volatile private var lastStop: Pair<Int, Long> = 0 to Long.MIN_VALUE / 2

private const val RECENT_STOP_MS = 10_000L

private fun recentlyStopped(port: Int): Boolean =
    lastStop.first == port && SystemClock.elapsedRealtime() - lastStop.second < RECENT_STOP_MS
internal const val PORT_POLL_MS = 200L

/** True once [isFree] reports [port] free, polling for up to [waitMs]. */
internal fun waitForPort(
    port: Int,
    waitMs: Long,
    isFree: (Int) -> Boolean,
    sleep: (Long) -> Unit = Thread::sleep,
): Boolean {
    var waited = 0L
    while (true) {
        if (isFree(port)) return true
        if (waited >= waitMs) return false
        sleep(PORT_POLL_MS)
        waited += PORT_POLL_MS
    }
}
