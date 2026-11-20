package jp.relative.tech.one_touch_controller.platform.windows.input

import java.awt.event.KeyEvent

/** Maps remote key names and Windows virtual key codes to Java AWT [KeyEvent] codes. */
object KeyMapping {
    // Names the phone is allowed to send, e.g. "enter", "ctrl+c", "alt+tab"
    val keyNames: Map<String, Int> = mapOf(
        "enter" to KeyEvent.VK_ENTER, "backspace" to KeyEvent.VK_BACK_SPACE, "tab" to KeyEvent.VK_TAB,
        "esc" to KeyEvent.VK_ESCAPE, "space" to KeyEvent.VK_SPACE, "delete" to KeyEvent.VK_DELETE,
        "up" to KeyEvent.VK_UP, "down" to KeyEvent.VK_DOWN, "left" to KeyEvent.VK_LEFT, "right" to KeyEvent.VK_RIGHT,
        "home" to KeyEvent.VK_HOME, "end" to KeyEvent.VK_END,
        "pageup" to KeyEvent.VK_PAGE_UP, "pagedown" to KeyEvent.VK_PAGE_DOWN,
        "ime" to KeyEvent.VK_KANJI,   // 半角/全角 key: switches the Japanese IME on/off
        "ctrl" to KeyEvent.VK_CONTROL, "alt" to KeyEvent.VK_ALT, "shift" to KeyEvent.VK_SHIFT, "win" to KeyEvent.VK_WINDOWS
    )

    // Turns one key name into a Java key code (a-z, 0-9, f1-f12 and the names above). null = not allowed.
    fun keyCodeFor(name: String): Int? {
        keyNames[name]?.let { return it }
        if (name.length == 1 && name[0] in 'a'..'z') return KeyEvent.VK_A + (name[0] - 'a')
        if (name.length == 1 && name[0] in '0'..'9') return KeyEvent.VK_0 + (name[0] - '0')
        Regex("f([1-9]|1[0-2])").matchEntire(name)?.let { return KeyEvent.VK_F1 + it.groupValues[1].toInt() - 1 }
        return null
    }

    fun isValidKeyCombo(combo: String): Boolean =
        combo.isNotEmpty() && combo.split("+").all { keyCodeFor(it) != null }

    // Letters, digits, space, Enter and Tab are pressed as keys. Everything else (punctuation, symbols,
    // non-English) is pasted, because Robot's symbol keys depend on the PC's keyboard layout (US vs JIS).
    fun liveKeyCode(c: Char): Int? = when (c) {
        in 'a'..'z' -> KeyEvent.VK_A + (c - 'a')
        in 'A'..'Z' -> KeyEvent.VK_A + (c - 'A')
        in '0'..'9' -> KeyEvent.VK_0 + (c - '0')
        ' ' -> KeyEvent.VK_SPACE
        '\n' -> KeyEvent.VK_ENTER
        '\t' -> KeyEvent.VK_TAB
        else -> null
    }

    /**
     * Windows key code (what the recorder hears) -> Java key code (what the Robot can press).
     * Most are the same number; the ones that differ are listed. null = a key the Robot cannot press.
     */
    fun windowsKeyToJava(vk: Int): Int? = when (vk) {
        13 -> KeyEvent.VK_ENTER
        45 -> KeyEvent.VK_INSERT
        46 -> KeyEvent.VK_DELETE
        91, 92 -> KeyEvent.VK_WINDOWS
        93 -> KeyEvent.VK_CONTEXT_MENU
        160, 161 -> KeyEvent.VK_SHIFT          // left / right Shift
        162, 163 -> KeyEvent.VK_CONTROL        // left / right Ctrl
        164, 165 -> KeyEvent.VK_ALT            // left / right Alt
        186 -> KeyEvent.VK_SEMICOLON
        187 -> KeyEvent.VK_EQUALS
        188 -> KeyEvent.VK_COMMA
        189 -> KeyEvent.VK_MINUS
        190 -> KeyEvent.VK_PERIOD
        191 -> KeyEvent.VK_SLASH
        192 -> KeyEvent.VK_BACK_QUOTE
        219 -> KeyEvent.VK_OPEN_BRACKET
        220 -> KeyEvent.VK_BACK_SLASH
        221 -> KeyEvent.VK_CLOSE_BRACKET
        222 -> KeyEvent.VK_QUOTE
        8, 9, 16, 17, 18, 19, 20, 27, 32, 44, 144, 145 -> vk      // Backspace, Tab, Shift, Ctrl, Alt, Pause, Caps, Esc, Space, PrtSc, NumLock, ScrollLock
        in 33..40 -> vk                                            // PgUp, PgDn, End, Home, arrows
        in 48..57 -> vk                                            // 0-9
        in 65..90 -> vk                                            // A-Z
        in 96..111 -> vk                                           // number pad
        in 112..123 -> vk                                          // F1-F12
        else -> null
    }
}
