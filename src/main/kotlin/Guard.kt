// Security helpers for the PC server:
//  - compares the token in a way that cannot be timed
//  - blocks a device for a few minutes after several wrong tokens
//  - knows whether a request came through the internet tunnel or from the local network
//  - refuses the most dangerous commands when they come through the internet (unless you allow them)

import io.ktor.server.application.ApplicationCall
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

object Guard {
    // Commands that are refused when they arrive through the internet tunnel (unless allowed in the window)
    val dangerousOverInternet = setOf("shutdown_60", "restart_60", "run_script_1")

    @Volatile
    var allowDangerousOverInternet = false

    private const val MAX_FAILS = 5                       // wrong tokens in a row ...
    private const val WINDOW_MS = 10 * 60 * 1000L        // ... within 10 minutes ...
    private const val BLOCK_MS = 5 * 60 * 1000L          // ... = blocked for 5 minutes

    private class Attempts {
        var fails = 0
        var windowStart = 0L
        var blockedUntil = 0L
    }

    private val attempts = ConcurrentHashMap<String, Attempts>()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val loopbackNames = setOf("::1", "0:0:0:0:0:0:0:1", "[::1]", "localhost")
    private val random = SecureRandom()

    // The address that is really connected to the server.
    // For internet requests this is cloudflared on this same PC, so it is always "127.0.0.1".
    // (call.request.local is the real network connection; headers cannot change it)
    private fun peer(call: ApplicationCall): String = call.request.local.remoteAddress

    // 127.x.x.x, ::1 and the "::ffff:127.0.0.1" form all mean "this PC"
    private fun isLoopback(address: String): Boolean {
        val a = address.lowercase().removePrefix("::ffff:")
        return a in loopbackNames || a.startsWith("127.")
    }

    // True when the request came in through the Cloudflare tunnel.
    // The header is only believed when the connection comes from this PC itself (= from cloudflared),
    // so somebody on the Wi-Fi cannot pretend to be an internet visitor or hide behind it.
    fun isViaTunnel(call: ApplicationCall): Boolean =
        isLoopback(peer(call)) && call.request.headers["CF-Connecting-IP"] != null

    // Who is asking? Internet visitors are told apart by the address Cloudflare reports.
    fun clientId(call: ApplicationCall): String {
        val p = peer(call)
        return if (isLoopback(p)) (call.request.headers["CF-Connecting-IP"] ?: p) else p
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
            val left = a.blockedUntil - System.currentTimeMillis()
            return if (left > 0) (left + 999) / 1000 else 0
        }
    }

    // Call after a wrong token. Returns true if this failure just caused a block.
    fun recordFailure(id: String): Boolean {
        val now = System.currentTimeMillis()
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
        val now = System.currentTimeMillis()
        val before = lastSeen.put(id, now)
        return before == null || now - before > 30 * 60 * 1000L
    }

    // A long random token (no look-alike characters such as 0/O or 1/l/I)
    fun generateToken(length: Int = 24): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        return (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }
}