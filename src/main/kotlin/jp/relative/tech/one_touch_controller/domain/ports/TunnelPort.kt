package jp.relative.tech.one_touch_controller.domain.ports

import kotlinx.coroutines.flow.StateFlow

data class TunnelState(
    val status: String = "Off",
    val url: String? = null,
    val online: Boolean = false,
    val active: Boolean = false
)

/** Port for internet access via an outgoing Cloudflare quick tunnel. */
interface TunnelPort {
    val state: StateFlow<TunnelState>

    fun findExecutable(): String?
    fun download(): String
    fun start(executablePath: String)
    fun stop()
}
