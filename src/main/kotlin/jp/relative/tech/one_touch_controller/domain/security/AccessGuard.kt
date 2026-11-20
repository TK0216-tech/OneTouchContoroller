package jp.relative.tech.one_touch_controller.domain.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Security rules and rate-limiting for the PC server (independent of Ktor):
 *  - compares tokens in constant time
 *  - blocks a device for a few minutes after several wrong tokens
 *  - holds runtime toggles for dangerous commands over internet and screen viewing
 */
class AccessGuard(
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val _allowDangerousOverInternet = MutableStateFlow(false)
    val allowDangerousOverInternet: StateFlow<Boolean> = _allowDangerousOverInternet.asStateFlow()

    // Switch in the PC window: may a phone look at this PC's screen? (works for the local network and the internet)
    private val _allowScreenView = MutableStateFlow(true)
    val allowScreenView: StateFlow<Boolean> = _allowScreenView.asStateFlow()

    fun setAllowDangerousOverInternet(allowed: Boolean) {
        _allowDangerousOverInternet.value = allowed
    }

    fun setAllowScreenView(allowed: Boolean) {
        _allowScreenView.value = allowed
    }

    private class Attempts {
        var fails = 0
        var windowStart = 0L
        var blockedUntil = 0L
    }

    private val attempts = ConcurrentHashMap<String, Attempts>()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val loopbackNames = setOf("::1", "0:0:0:0:0:0:0:1", "[::1]", "localhost")
    private val random = SecureRandom()

    // 127.x.x.x, ::1 and the "::ffff:127.0.0.1" form all mean "this PC"
    fun isLoopback(address: String): Boolean {
        val a = address.lowercase().removePrefix("::ffff:")
        return a in loopbackNames || a.startsWith("127.")
    }

    // Compares two tokens without leaking (through timing) how many characters were right
    fun tokensEqual(given: String?, expected: String): Boolean {
        if (given == null) return false
        val sha = MessageDigest.getInstance("SHA-256")
        return MessageDigest.isEqual(sha.digest(given.toByteArray()), sha.digest(expected.toByteArray()))
    }

    // Seconds this device is still blocked (0 = not blocked)
    fun blockedSeconds(id: String): Long {
        val a = attempts[id] ?: return 0
        synchronized(a) {
            val left = a.blockedUntil - clock()
            return if (left > 0) (left + 999) / 1000 else 0
        }
    }

    // Call after a wrong token. Returns true if this failure just caused a block.
    fun recordFailure(id: String): Boolean {
        val now = clock()
        if (attempts.size > 2000) {   // keep the list small
            attempts.entries.removeIf { it.value.blockedUntil < now && now - it.value.windowStart > WINDOW_MS }
        }
        val a = attempts.getOrPut(id) { Attempts() }
        synchronized(a) {
            if (now - a.windowStart > WINDOW_MS) {
                a.fails = 0
                a.windowStart = now
            }
            a.fails++
            if (a.fails >= MAX_FAILS) {
                a.blockedUntil = now + BLOCK_MS
                a.fails = 0
                a.windowStart = now
                return true
            }
            return false
        }
    }

    // Call after a correct token. Returns true if this device was not seen for a while (= log "connected").
    fun recordSuccess(id: String): Boolean {
        attempts.remove(id)
        val now = clock()
        val before = lastSeen.put(id, now)
        return before == null || now - before > RECONNECT_LOG_INTERVAL_MS
    }

    // A long random token (no look-alike characters such as 0/O or 1/l/I)
    fun generateToken(length: Int = 24): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        return (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    companion object {
        private const val MAX_FAILS = 5                       // wrong tokens in a row ...
        private const val WINDOW_MS = 10 * 60 * 1000L        // ... within 10 minutes ...
        private const val BLOCK_MS = 5 * 60 * 1000L          // ... = blocked for 5 minutes
        private const val RECONNECT_LOG_INTERVAL_MS = 30 * 60 * 1000L
    }
}
