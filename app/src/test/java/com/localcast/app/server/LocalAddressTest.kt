package com.localcast.app.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The CIO engine reports the local IP a connection arrived on (what HttpServer passes to Signaling as localIp). */
class LocalAddressTest {
    @Test fun cioReportsTheLocalIpOfTheConnection() {
        val server = embeddedServer(
            CIO,
            configure = { connectors.add(EngineConnectorBuilder().apply { port = 0; host = "0.0.0.0" }) },
        ) {
            routing { get("/local") { call.respondText(plainIp(call.request.local.localAddress) ?: "null") } }
        }
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val c = URL("http://127.0.0.1:$port/local").openConnection() as HttpURLConnection
            assertEquals("127.0.0.1", c.inputStream.bufferedReader().readText())
        } finally {
            server.stop(0, 0)
        }
    }
}
