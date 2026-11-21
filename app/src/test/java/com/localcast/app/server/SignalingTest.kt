package com.localcast.app.server

import com.localcast.app.rtc.ViewerSession
import com.localcast.app.rtc.ViewerSessionFactory
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalingTest {
    private class FakeSession(override val viewerId: String, val send: (ServerMessage) -> Unit) : ViewerSession {
        val events = CopyOnWriteArrayList<String>()
        @Volatile var closed = false
        override fun onAnswer(sdp: String) { events += "answer:$sdp" }
        override fun onRemoteIce(candidate: IceCandidateDto) { events += "ice:${candidate.candidate}" }
        override fun restart() { events += "restart" }
        override fun onReport(stats: ViewerStats) { events += "report:${stats.fps}" }
        override fun close() { closed = true }
    }

    private class FakeFactory : ViewerSessionFactory {
        @Volatile override var isStreaming = true
        val created = CopyOnWriteArrayList<FakeSession>()
        override fun createViewer(viewerId: String, remoteAddress: String, send: (ServerMessage) -> Unit): ViewerSession? {
            val s = FakeSession(viewerId, send)
            created += s
            send(ServerMessage.Offer("offer-$viewerId"))
            return s
        }
    }

    private class Events : Signaling.Listener {
        val log = CopyOnWriteArrayList<String>()
        override fun onViewerJoined(viewerId: String, remoteIp: String, userAgent: String?) { log += "join:$viewerId:$userAgent" }
        override fun onViewerLeft(viewerId: String) { log += "left:$viewerId" }
    }

    private suspend fun waitFor(cond: () -> Boolean) = withTimeout(5_000) { while (!cond()) delay(10) }

    private fun run(
        factory: FakeFactory = FakeFactory(),
        max: Int? = 3,
        block: suspend (io.ktor.client.HttpClient, Signaling, PinGuard, FakeFactory, Events) -> Unit,
    ) {
        val guard = PinGuard()
        val events = Events()
        val signaling = if (max == null) Signaling(guard, factory, { true }, events) else Signaling(guard, factory, { true }, events, max)
        runBlocking {
            testApplication {
                application {
                    install(WebSockets)
                    routing { webSocket("/ws") { signaling.handle(this, "1.2.3.4") } }
                }
                val client = createClient { install(ClientWebSockets) }
                block(client, signaling, guard, factory, events)
            }
        }
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.recv(): ServerMessage =
        withTimeout(5_000) {
            val f = incoming.receive() as Frame.Text
            Protocol.json.decodeFromString(ServerMessage.serializer(), f.readText())
        }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.sendC(m: ClientMessage) =
        send(Frame.Text(Protocol.json.encodeToString(ClientMessage.serializer(), m)))

    @Test fun happyPathRoutesMessagesAndCleansUp() = run { client, signaling, guard, factory, events ->
        client.webSocket("/ws") {
            sendC(ClientMessage.Hello(pin = guard.pin, userAgent = "UA"))
            val w = recv() as ServerMessage.Welcome
            assertEquals("v1", w.viewerId)
            assertEquals(ServerMessage.Offer("offer-v1"), recv())
            sendC(ClientMessage.Answer("A"))
            sendC(ClientMessage.Ice(IceCandidateDto("c1")))
            send(Frame.Text("not json"))
            sendC(ClientMessage.Restart)
            sendC(ClientMessage.Report(ViewerStats(fps = 30.0)))
            val s = factory.created.single()
            waitFor { s.events.size == 4 }
            assertEquals(listOf("answer:A", "ice:c1", "restart", "report:30.0"), s.events.toList())
            assertEquals(1, signaling.viewerCount)
            signaling.broadcast(ServerMessage.State(true, 1, 2, 3))
            assertEquals(ServerMessage.State(true, 1, 2, 3), recv())
            sendC(ClientMessage.Bye)
            waitFor { s.closed }
        }
        waitFor { events.log.contains("left:v1") }
        assertEquals(listOf("join:v1:UA", "left:v1"), events.log.toList())
        assertEquals(0, signaling.viewerCount)
    }

    @Test fun wrongPinDenied() = run { client, _, guard, _, _ ->
        client.webSocket("/ws") {
            sendC(ClientMessage.Hello(pin = if (guard.pin == "000000") "111111" else "000000"))
            assertEquals(ServerMessage.Denied(DenyReason.PIN), recv())
        }
    }

    @Test fun badFirstMessage() = run { client, _, _, _, _ ->
        client.webSocket("/ws") {
            sendC(ClientMessage.Bye)
            assertEquals(ServerMessage.Denied(DenyReason.BAD_REQUEST), recv())
        }
    }

    @Test fun notStreaming() = run(FakeFactory().also { it.isStreaming = false }) { client, _, guard, _, _ ->
        client.webSocket("/ws") {
            sendC(ClientMessage.Hello(pin = guard.pin))
            assertEquals(ServerMessage.Denied(DenyReason.NOT_STREAMING), recv())
        }
    }

    @Test fun defaultSignalingHasNoViewerCap() = run(max = null) { client, signaling, guard, _, _ ->
        val n = 8
        val welcomed = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            repeat(n) {
                launch {
                    client.webSocket("/ws") {
                        sendC(ClientMessage.Hello(pin = guard.pin))
                        assertTrue(recv() is ServerMessage.Welcome)
                        welcomed.incrementAndGet()
                        release.await()
                    }
                }
            }
            waitFor { welcomed.get() == n }
            assertEquals(n, signaling.viewerCount)
            release.complete(Unit)
        }
    }

    @Test fun limitAndReconnectReplacesOld() = run(max = 1) { client, signaling, guard, factory, _ ->
        client.webSocket("/ws") {
            sendC(ClientMessage.Hello(pin = guard.pin))
            val token = (recv() as ServerMessage.Welcome).token
            recv() // offer
            // second viewer with PIN → full
            client.webSocket("/ws") {
                sendC(ClientMessage.Hello(pin = guard.pin))
                assertEquals(ServerMessage.Denied(DenyReason.FULL), recv())
            }
            // reconnect with same token → allowed, old one is closed
            client.webSocket("/ws") {
                sendC(ClientMessage.Hello(token = token))
                assertEquals("v3", (recv() as ServerMessage.Welcome).viewerId)
                waitFor { factory.created.size == 2 && factory.created[0].closed }
                assertEquals(1, signaling.viewerCount)
            }
        }
        waitFor { signaling.viewerCount == 0 }
        assertTrue(factory.created.all { it.closed })
    }

    @Test fun disconnectAllSendsBye() = run { client, signaling, guard, _, _ ->
        client.webSocket("/ws") {
            sendC(ClientMessage.Hello(pin = guard.pin))
            recv(); recv()
            signaling.disconnectAll("stopped")
            assertEquals(ServerMessage.Bye("stopped"), recv())
        }
        waitFor { signaling.viewerCount == 0 }
    }
}
