package jp.relative.tech.one_touch_controller.domain.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// Plain Guard mode logic (no Windows calls, so it can be tested anywhere).

object GuardPassword {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 32

    /** Passwords use letters and numbers only and do not care about capital letters ("abc1" = "ABC1"). */
    fun normalize(text: String): String = text.trim().uppercase()

    fun isValid(text: String): Boolean {
        val p = normalize(text)
        return p.length in MIN_LENGTH..MAX_LENGTH && p.all { it in 'A'..'Z' || it in '0'..'9' }
    }

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    /** Slow on purpose (PBKDF2), so a stolen settings file does not reveal the password quickly. */
    fun hash(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(normalize(password).toCharArray(), salt, 120_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun matches(password: String, salt: ByteArray, expected: ByteArray): Boolean =
        MessageDigest.isEqual(hash(password, salt), expected)
}

/** Windows key code -> the character it types in the password box (letters, top-row digits, number pad). */
fun guardKeyChar(vk: Int): Char? = when (vk) {
    in 0x30..0x39 -> '0' + (vk - 0x30)
    in 0x41..0x5A -> 'A' + (vk - 0x41)
    in 0x60..0x69 -> '0' + (vk - 0x60)
    else -> null
}

/** Counts wrong passwords typed at the PC. Every 5th wrong one makes the person wait a minute. */
class GuardAttempts(private val waitMs: Long = 60_000) {
    @Volatile var wrong = 0
        private set
    @Volatile var lockedUntil = 0L
        private set

    fun secondsLeft(now: Long): Long = if (lockedUntil > now) (lockedUntil - now + 999) / 1000 else 0

    /** Returns true if this wrong password started a wait. */
    fun recordWrong(now: Long): Boolean {
        wrong++
        if (wrong % 5 == 0) {
            lockedUntil = now + waitMs
            return true
        }
        return false
    }

    fun reset() {
        wrong = 0
        lockedUntil = 0
    }
}

/**
 * Reads  powercfg /query  output. The language of Windows does not matter: the last two lines that end
 * with a value like ": 0x00000001" are "plugged in" (AC) and "on battery" (DC). Returns null if not found.
 */
fun parsePowercfgValues(output: String): Pair<Int, Int>? {
    val values = output.lines().mapNotNull { line ->
        Regex(":\\s*0x([0-9a-fA-F]+)\\s*$").find(line.trim())?.groupValues?.get(1)?.toLongOrNull(16)?.toInt()
    }
    if (values.size < 2) return null
    return values[values.size - 2] to values[values.size - 1]
}

/** Typed status message shown under the password box on the lock screen (avoids matching translated strings). */
sealed interface GuardPromptState {
    object None : GuardPromptState
    object Checking : GuardPromptState
    object WrongPassword : GuardPromptState
    data class TooManyWrong(val waitSeconds: Long) : GuardPromptState
}
