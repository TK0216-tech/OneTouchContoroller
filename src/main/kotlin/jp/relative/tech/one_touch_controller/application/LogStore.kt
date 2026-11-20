package jp.relative.tech.one_touch_controller.application

import jp.relative.tech.one_touch_controller.domain.config.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Thread-safe activity log backed by [StateFlow] (safe to call from Ktor, Swing, JNA and background threads).
 */
class LogStore(
    private val maxEntries: Int = 500,
    private val timeProvider: () -> LocalTime = LocalTime::now
) : AppLogger {
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    override fun log(message: String) {
        val time = timeProvider().format(formatter)
        val entry = "[$time] $message"
        _lines.update { current ->
            val next = current + entry
            if (next.size > maxEntries) next.drop(next.size - maxEntries) else next
        }
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
