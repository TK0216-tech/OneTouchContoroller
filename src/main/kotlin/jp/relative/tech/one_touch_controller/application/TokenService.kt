package jp.relative.tech.one_touch_controller.application

import jp.relative.tech.one_touch_controller.domain.config.AppLogger
import jp.relative.tech.one_touch_controller.domain.config.DEFAULT_SERVER_TOKEN
import jp.relative.tech.one_touch_controller.domain.config.MIN_REMOTE_TOKEN_LENGTH
import jp.relative.tech.one_touch_controller.domain.security.AccessGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

sealed interface TokenApplyResult {
    object Success : TokenApplyResult
    object Empty : TokenApplyResult
    data class TooShortForInternet(val minLength: Int) : TokenApplyResult
}

/**
 * Manages the access token persisted in `~/.onetouch_token` and validates updates.
 */
class TokenService(
    private val tokenFile: File,
    private val accessGuard: AccessGuard,
    private val logger: AppLogger
) {
    private val _token = MutableStateFlow(DEFAULT_SERVER_TOKEN)
    val token: StateFlow<String> = _token.asStateFlow()

    val current: String get() = _token.value

    fun load() {
        try {
            if (tokenFile.exists()) {
                val saved = tokenFile.readText().trim()
                if (saved.isNotEmpty()) _token.value = saved
            }
        } catch (e: Exception) {
            // ignore: we simply keep the default token
        }
    }

    fun generate(): String = accessGuard.generateToken()

    fun apply(rawToken: String, tunnelActive: Boolean): TokenApplyResult {
        val newToken = rawToken.trim()
        if (newToken.isEmpty()) return TokenApplyResult.Empty
        if (tunnelActive && newToken.length < MIN_REMOTE_TOKEN_LENGTH) {
            return TokenApplyResult.TooShortForInternet(MIN_REMOTE_TOKEN_LENGTH)
        }
        _token.value = newToken
        save(newToken)
        logger.log("Token updated")
        return TokenApplyResult.Success
    }

    private fun save(value: String) {
        try {
            tokenFile.writeText(value)
        } catch (e: Exception) {
            logger.log("Could not save token: ${e.message}")
        }
    }
}
