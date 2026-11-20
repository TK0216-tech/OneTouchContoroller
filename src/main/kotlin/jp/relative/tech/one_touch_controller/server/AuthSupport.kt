package jp.relative.tech.one_touch_controller.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import jp.relative.tech.one_touch_controller.application.TokenService
import jp.relative.tech.one_touch_controller.domain.config.AppLogger
import jp.relative.tech.one_touch_controller.domain.security.AccessGuard

/**
 * Ktor-specific authentication and client-identification helpers backed by [AccessGuard].
 */
class AuthSupport(
    val accessGuard: AccessGuard,
    private val tokenService: TokenService,
    private val logger: AppLogger
) {
    // The address that is really connected to the server.
    // For internet requests this is cloudflared on this same PC, so it is always "127.0.0.1".
    // (call.request.local is the real network connection; headers cannot change it)
    private fun peer(call: ApplicationCall): String = call.request.local.remoteAddress

    // True when the request came in through the Cloudflare tunnel.
    // The header is only believed when the connection comes from this PC itself (= from cloudflared),
    // so somebody on the Wi-Fi cannot pretend to be an internet visitor or hide behind it.
    fun isViaTunnel(call: ApplicationCall): Boolean =
        accessGuard.isLoopback(peer(call)) && call.request.headers["CF-Connecting-IP"] != null

    // Who is asking? Internet visitors are told apart by the address Cloudflare reports.
    fun clientId(call: ApplicationCall): String {
        val p = peer(call)
        return if (accessGuard.isLoopback(p)) (call.request.headers["CF-Connecting-IP"] ?: p) else p
    }

    fun expectedToken(): String = tokenService.current

    // Returns true if the phone sent the right token; otherwise answers 401 or 429.
    suspend fun checkToken(call: ApplicationCall): Boolean {
        val id = clientId(call)

        val wait = accessGuard.blockedSeconds(id)
        if (wait > 0) {                              // too many wrong tokens recently
            call.respondText(
                "Too many wrong tokens. Try again in $wait seconds.",
                status = HttpStatusCode.TooManyRequests
            )
            return false
        }

        if (accessGuard.tokensEqual(call.request.headers["X-Auth-Token"], expectedToken())) {
            if (accessGuard.recordSuccess(id)) {
                logger.log("Device connected: $id" + if (isViaTunnel(call)) " (via the internet)" else "")
            }
            return true
        }

        val nowBlocked = accessGuard.recordFailure(id)
        logger.log("Rejected a request from $id: wrong token" + if (nowBlocked) " - blocked for 5 minutes" else "")
        call.respond(HttpStatusCode.Unauthorized)
        return false
    }
}
