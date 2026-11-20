package jp.relative.tech.one_touch_controller.application

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/** Tracks how many phones are watching the PC's screen right now (the window shows a warning while > 0). */
class ScreenStreamState {
    private val counter = AtomicInteger(0)
    private val _viewers = MutableStateFlow(0)
    val viewers: StateFlow<Int> = _viewers.asStateFlow()

    fun started() {
        _viewers.value = counter.incrementAndGet()
    }

    fun stopped() {
        _viewers.value = counter.decrementAndGet().coerceAtLeast(0)
    }
}
