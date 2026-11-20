package jp.relative.tech.one_touch_controller.domain.ports

enum class MouseButton { LEFT, RIGHT }

/** Port for OS-level mouse, keyboard, clipboard and screenshot operations. */
interface PcInputPort {
    fun moveMouseBy(dx: Int, dy: Int)
    fun moveMouseToFraction(x: Int, y: Int)           // x, y in 0..1000
    fun click(button: MouseButton)
    fun doubleClick()
    fun setLeftButtonDown(down: Boolean)
    fun scroll(amount: Int)

    fun isValidKeyCombo(combo: String): Boolean
    fun pressKeyCombo(combo: String)

    suspend fun typeText(text: String)
    suspend fun liveType(backspaces: Int, text: String)

    fun takeScreenshot(): String
    fun copyToClipboard(text: String)
}
