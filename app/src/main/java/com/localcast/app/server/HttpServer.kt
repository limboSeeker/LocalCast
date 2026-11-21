package com.localcast.app.server

import android.content.res.AssetManager
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
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
    private val info: () -> ServerInfo,
) {
    private val lock = Any()
    private var engine: EmbeddedServer<*, *>? = null

    /** Binds the first free port in `preferredPort until preferredPort + attempts`; returns it. */
    fun start(preferredPort: Int, attempts: Int = 10): Int = synchronized(lock) {
        check(engine == null) { "already started" }
        var last: Throwable? = null
        for (port in preferredPort until preferredPort + attempts) {
            val candidate = createEngine(port)
            try {
                candidate.start(wait = false)
                engine = candidate
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

    fun stop() {
        val e = synchronized(lock) { engine.also { engine = null } } ?: return
        try {
            e.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        } catch (t: Throwable) {
            Log.w(TAG, "stop failed", t)
        }
    }

    private fun createEngine(port: Int) = embeddedServer(CIO, port = port, host = "0.0.0.0") {
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
                val origin = call.request.headers[HttpHeaders.Origin]
                val host = call.request.headers[HttpHeaders.Host]
                if (origin != null && !sameOrigin(origin, host)) {
                    Log.w(TAG, "rejected WebSocket from foreign origin $origin (host $host)")
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin"))
                    return@webSocket
                }
                signaling.handle(this, call.request.origin.remoteAddress)
            }
        }
    }

    private fun sameOrigin(origin: String, host: String?): Boolean {
        if (host == null) return false
        val authority = runCatching { URI(origin).rawAuthority }.getOrNull() ?: return false
        return authority.equals(host, ignoreCase = true)
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
