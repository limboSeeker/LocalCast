package com.localcast.app.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

sealed interface AuthResult {
    data class Ok(val token: String) : AuthResult
    data class Denied(val reason: DenyReason, val retryAfterSec: Int? = null) : AuthResult
}

/**
 * PIN + session-token authentication with per-IP brute-force protection:
 * [MAX_FAILURES] wrong PINs within [FAILURE_WINDOW_MS] block the IP for [LOCK_MS].
 * Thread-safe.
 */
class PinGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private class Attempts {
        val failures = ArrayDeque<Long>()
        var lockedUntil = 0L
    }

    private val lock = Any()
    private val attempts = HashMap<String, Attempts>()
    private val tokens = LinkedHashSet<String>() // insertion order → evict oldest
    private var currentPin: String = newPin()
    private var lastCleanup = 0L

    val pin: String get() = synchronized(lock) { currentPin }

    fun regeneratePin(): String = synchronized(lock) {
        currentPin = newPin()
        tokens.clear()
        currentPin
    }

    fun revokeAll() = synchronized(lock) { tokens.clear() }

    fun authenticate(remoteIp: String, pin: String?, token: String?, pinRequired: Boolean): AuthResult =
        synchronized(lock) {
            val now = clock()
            cleanup(now)
            val state = attempts[remoteIp]
            if (state != null) {
                if (state.lockedUntil > now) {
                    return AuthResult.Denied(DenyReason.LOCKED, remainingSec(state.lockedUntil - now))
                }
                if (state.lockedUntil != 0L) attempts.remove(remoteIp) // lock expired → fresh start
            }

            if (token != null && tokens.contains(token)) return AuthResult.Ok(token)
            if (!pinRequired) return AuthResult.Ok(issueToken())

            if (pin == null) {
                return AuthResult.Denied(if (token != null) DenyReason.TOKEN else DenyReason.PIN)
            }

            if (pinsEqual(pin, currentPin)) {
                attempts.remove(remoteIp)
                return AuthResult.Ok(issueToken())
            }

            val a = attempts.getOrPut(remoteIp) { Attempts() }
            a.failures.addLast(now)
            while (a.failures.isNotEmpty() && now - a.failures.first() > FAILURE_WINDOW_MS) a.failures.removeFirst()
            if (a.failures.size >= MAX_FAILURES) {
                a.lockedUntil = now + LOCK_MS
                a.failures.clear()
                return AuthResult.Denied(DenyReason.LOCKED, remainingSec(LOCK_MS))
            }
            AuthResult.Denied(DenyReason.PIN)
        }

    private fun remainingSec(ms: Long): Int = ((ms + 999) / 1000).toInt()

    private fun pinsEqual(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun issueToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val t = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        if (tokens.size >= MAX_TOKENS) tokens.remove(tokens.first())
        tokens.add(t)
        return t
    }

    private fun newPin(): String = "%06d".format(random.nextInt(1_000_000))

    /** Drops stale failure records so [attempts] cannot grow without bound. */
    private fun cleanup(now: Long) {
        if (now - lastCleanup < CLEANUP_INTERVAL_MS && attempts.size < CLEANUP_SIZE_HINT) return
        lastCleanup = now
        val it = attempts.entries.iterator()
        while (it.hasNext()) {
            val a = it.next().value
            if (a.lockedUntil > now) continue
            val newest = a.failures.lastOrNull() ?: a.lockedUntil
            if (now - newest > FAILURE_WINDOW_MS) it.remove()
        }
    }

    companion object {
        const val MAX_FAILURES = 5
        const val FAILURE_WINDOW_MS = 10 * 60 * 1000L
        const val LOCK_MS = 60_000L
        private const val CLEANUP_INTERVAL_MS = 60_000L
        private const val CLEANUP_SIZE_HINT = 256
        /** Upper bound for remembered viewer tokens (oldest are evicted first). */
        const val MAX_TOKENS = 256
    }
}
