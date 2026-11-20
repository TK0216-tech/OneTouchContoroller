package jp.relative.tech.one_touch_controller.domain.ports

import kotlinx.coroutines.flow.StateFlow

data class GuardState(
    val active: Boolean = false,
    val hasPassword: Boolean = false
)

/** Port for Guard mode (blocking local mouse/keyboard, lock overlay and owner messages). */
interface GuardEnginePort {
    val state: StateFlow<GuardState>

    fun load()
    fun setPassword(text: String): String?
    fun turnOn(who: String)
    fun turnOff(who: String)
    fun showMessage(text: String)
    fun statusText(): String
}
