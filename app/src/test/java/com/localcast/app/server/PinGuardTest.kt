package com.localcast.app.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinGuardTest {
    private var now = 1_000_000L
    private val guard = PinGuard(clock = { now })
    private val ip = "192.168.0.5"

    private fun wrong() = if (guard.pin == "000000") "111111" else "000000"

    @Test fun pinIsSixDigits() {
        repeat(200) {
            val p = PinGuard().regeneratePin()
            assertTrue(p, Regex("\\d{6}").matches(p))
        }
    }

    @Test fun correctPinGivesToken() {
        val r = guard.authenticate(ip, guard.pin, null, true)
        assertTrue(r is AuthResult.Ok)
        assertTrue(Regex("[A-Za-z0-9_-]{22}").matches((r as AuthResult.Ok).token))
    }

    @Test fun wrongPinDenied() {
        assertEquals(AuthResult.Denied(DenyReason.PIN), guard.authenticate(ip, wrong(), null, true))
    }

    @Test fun missingPinAndToken() {
        assertEquals(AuthResult.Denied(DenyReason.PIN), guard.authenticate(ip, null, null, true))
        assertEquals(AuthResult.Denied(DenyReason.TOKEN), guard.authenticate(ip, null, "nope", true))
    }

    @Test fun validTokenReturnsSameToken() {
        val t = (guard.authenticate(ip, guard.pin, null, true) as AuthResult.Ok).token
        assertEquals(AuthResult.Ok(t), guard.authenticate(ip, null, t, true))
    }

    @Test fun lockAfterFiveFailuresAndUnlockAfter60s() {
        repeat(4) { assertEquals(AuthResult.Denied(DenyReason.PIN), guard.authenticate(ip, wrong(), null, true)) }
        assertEquals(AuthResult.Denied(DenyReason.LOCKED, 60), guard.authenticate(ip, wrong(), null, true))
        now += 10_000
        assertEquals(AuthResult.Denied(DenyReason.LOCKED, 50), guard.authenticate(ip, guard.pin, null, true))
        // other IP unaffected
        assertTrue(guard.authenticate("10.0.0.9", guard.pin, null, true) is AuthResult.Ok)
        now += 50_000
        assertTrue(guard.authenticate(ip, guard.pin, null, true) is AuthResult.Ok)
    }

    @Test fun successResetsCounter() {
        repeat(4) { guard.authenticate(ip, wrong(), null, true) }
        assertTrue(guard.authenticate(ip, guard.pin, null, true) is AuthResult.Ok)
        repeat(4) { assertEquals(AuthResult.Denied(DenyReason.PIN), guard.authenticate(ip, wrong(), null, true)) }
    }

    @Test fun failuresOutsideWindowForgotten() {
        repeat(4) { guard.authenticate(ip, wrong(), null, true) }
        now += 11 * 60_000
        assertEquals(AuthResult.Denied(DenyReason.PIN), guard.authenticate(ip, wrong(), null, true))
    }

    @Test fun regenerateRevokesTokensAndChangesPin() {
        val t = (guard.authenticate(ip, guard.pin, null, true) as AuthResult.Ok).token
        val old = guard.pin
        var p = guard.regeneratePin()
        while (p == old) p = guard.regeneratePin()
        assertEquals(p, guard.pin)
        assertEquals(AuthResult.Denied(DenyReason.TOKEN), guard.authenticate(ip, null, t, true))
    }

    @Test fun revokeAll() {
        val t = (guard.authenticate(ip, guard.pin, null, true) as AuthResult.Ok).token
        guard.revokeAll()
        assertEquals(AuthResult.Denied(DenyReason.TOKEN), guard.authenticate(ip, null, t, true))
    }

    @Test fun pinNotRequired() {
        val a = guard.authenticate(ip, null, null, false) as AuthResult.Ok
        val b = guard.authenticate(ip, null, null, false) as AuthResult.Ok
        assertNotEquals(a.token, b.token)
        assertEquals(a, guard.authenticate(ip, null, a.token, false))
    }
}
