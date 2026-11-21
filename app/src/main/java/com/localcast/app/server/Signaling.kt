package com.localcast.app.server

import android.util.Log
import com.localcast.app.rtc.ViewerSession
import com.localcast.app.rtc.ViewerSessionFactory
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** WebSocket signaling: handshake/auth, viewer registry, and message routing to the WebRTC engine. */
class Signaling(
    private val pinGuard: PinGuard,
    private val sessions: ViewerSessionFactory,
    private val pinRequired: () -> Boolean,
    private val listener: Listener,
    /** No cap by default: each viewer gets its own encoder; when hardware encoders run out, new viewers fall back to software VP8. */
    private val maxViewers: Int = Int.MAX_VALUE,
) {
    interface Listener {
        fun onViewerJoined(viewerId: String, remoteIp: String, userAgent: String?)
        fun onViewerLeft(viewerId: String)
    }

    private class Conn(val viewerId: String, val token: String, val out: Channel<ServerMessage>) {
        @Volatile var writer: Job? = null
    }

    private val lock = Any()
    private val conns = LinkedHashMap<String, Conn>() // by viewerId
    private val nextId = AtomicInteger(0)

    val viewerCount: Int get() = synchronized(lock) { conns.size }

    fun broadcast(message: ServerMessage) {
        val snapshot = synchronized(lock) { conns.values.toList() }
        snapshot.forEach { it.out.trySend(message) }
    }

    suspend fun disconnectAll(reason: String) {
        val snapshot = synchronized(lock) { conns.values.toList() }
        snapshot.forEach {
            it.out.trySend(ServerMessage.Bye(reason))
            it.out.close()
        }
        withTimeoutOrNull(FLUSH_TIMEOUT_MS) { snapshot.forEach { it.writer?.join() } }
    }

    suspend fun handle(socket: DefaultWebSocketServerSession, remoteIp: String) = coroutineScope {
        val out = Channel<ServerMessage>(Channel.UNLIMITED)
        val writer = launch { writeLoop(socket, out) }
        var conn: Conn? = null
        var session: ViewerSession? = null
        try {
            val hello = withTimeoutOrNull(HELLO_TIMEOUT_MS) { readHello(socket) }
            if (hello == null) {
                out.send(ServerMessage.Denied(DenyReason.BAD_REQUEST))
                return@coroutineScope
            }

            val auth = pinGuard.authenticate(remoteIp, hello.pin, hello.token, pinRequired())
            if (auth is AuthResult.Denied) {
                if (hello.pin != null) delay(WRONG_PIN_DELAY_MS)
                out.send(ServerMessage.Denied(auth.reason, auth.retryAfterSec))
                return@coroutineScope
            }
            val token = (auth as AuthResult.Ok).token

            if (!sessions.isStreaming) {
                out.send(ServerMessage.Denied(DenyReason.NOT_STREAMING))
                return@coroutineScope
            }

            val viewerId = "v${nextId.incrementAndGet()}"
            val newConn = Conn(viewerId, token, out).also { it.writer = writer }
            var replaced: Conn? = null
            val full = synchronized(lock) {
                replaced = conns.values.firstOrNull { it.token == token }
                val others = conns.size - if (replaced != null) 1 else 0
                if (others >= maxViewers) {
                    true
                } else {
                    replaced?.let { conns.remove(it.viewerId) }
                    conns[viewerId] = newConn
                    false
                }
            }
            if (full) {
                out.send(ServerMessage.Denied(DenyReason.FULL))
                return@coroutineScope
            }
            conn = newConn
            replaced?.out?.close() // old socket is closed by its own writer; its handle() cleans up

            out.send(ServerMessage.Welcome(token, viewerId))
            listener.onViewerJoined(viewerId, remoteIp, hello.userAgent)

            session = try {
                sessions.createViewer(viewerId, remoteIp) { msg -> newConn.out.trySend(msg) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "createViewer failed", e)
                null
            }
            if (session == null) {
                out.send(ServerMessage.Denied(DenyReason.NOT_STREAMING))
                return@coroutineScope
            }

            receiveLoop(socket, session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "socket error for $remoteIp", e)
        } finally {
            withContext(NonCancellable) {
                try {
                    session?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "session close failed", e)
                }
                conn?.let { c -> synchronized(lock) { conns.remove(c.viewerId) } }
                out.close()
                withTimeoutOrNull(FLUSH_TIMEOUT_MS) { writer.join() }
                writer.cancel()
                if (conn != null) {
                    try {
                        listener.onViewerLeft(conn.viewerId)
                    } catch (e: Exception) {
                        Log.w(TAG, "onViewerLeft failed", e)
                    }
                }
            }
        }
    }

    /** Reads frames until the first valid Hello; anything else is a bad request (null). */
    private suspend fun readHello(socket: DefaultWebSocketServerSession): ClientMessage.Hello? {
        for (frame in socket.incoming) {
            if (frame !is Frame.Text) continue
            return decode(frame.readText()) as? ClientMessage.Hello
        }
        return null
    }

    private suspend fun receiveLoop(socket: DefaultWebSocketServerSession, session: ViewerSession) {
        for (frame in socket.incoming) {
            if (frame !is Frame.Text) continue
            val msg = decode(frame.readText()) ?: continue
            try {
                when (msg) {
                    is ClientMessage.Answer -> session.onAnswer(msg.sdp)
                    is ClientMessage.Ice -> session.onRemoteIce(msg.candidate)
                    ClientMessage.Restart -> session.restart()
                    is ClientMessage.Report -> session.onReport(msg.stats)
                    ClientMessage.Bye -> return
                    is ClientMessage.Hello -> Log.w(TAG, "unexpected hello, ignored")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "viewer session error", e)
            }
        }
    }

    private fun decode(text: String): ClientMessage? = try {
        Protocol.decodeClient(text)
    } catch (e: IllegalArgumentException) { // SerializationException is a subtype
        Log.w(TAG, "bad message skipped: ${e.message}")
        null
    }

    private suspend fun writeLoop(socket: DefaultWebSocketServerSession, out: Channel<ServerMessage>) {
        try {
            for (m in out) socket.send(Frame.Text(Protocol.encodeServer(m)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            out.cancel() // socket gone: stop accepting, drop the rest
        } finally {
            withContext(NonCancellable) {
                try {
                    socket.close(CloseReason(CloseReason.Codes.NORMAL, ""))
                } catch (_: Exception) {
                }
            }
        }
    }

    private companion object {
        const val TAG = "LocalCast/Signaling"
        const val HELLO_TIMEOUT_MS = 10_000L
        const val WRONG_PIN_DELAY_MS = 300L
        const val FLUSH_TIMEOUT_MS = 1_000L
    }
}
