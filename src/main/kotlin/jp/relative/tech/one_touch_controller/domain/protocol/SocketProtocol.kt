package jp.relative.tech.one_touch_controller.domain.protocol

// The short text messages the phone / web page sends over the fast "live connection" (WebSocket) at /ws.
//
//   auth <token>      first message after connecting (the server answers "ok" or "denied")
//   m <dx> <dy>       move the mouse by dx, dy pixels
//   c left|right      click
//   s <amount>        scroll (positive = down)
//   p <number>        ping; the server answers "q <number>" so the phone can measure the delay
//
// Screen viewing (the PC sends its screen as small JPEG pictures, one binary message per picture):
//   v <width> <quality> <zoom%> <cx> <cy>   start, or change, the stream. width 320-1920 px, quality 10-90,
//                                           zoom 100-400 (percent), cx/cy = center of the part to show (0-1000)
//   v off             stop the stream
//   a                 "picture received": the PC only sends the next picture after that (keeps it from piling up)
//   u <x> <y>         move the mouse to an exact spot; x and y are 0-1000 = position on the whole screen
//   c double -          double click
//   b down | b up -     hold / release the left mouse button (for dragging)
//
// Direct typing (the keyboard of the device you are using types on the PC):
//   t <text>          type this text. Everything after "t " is the text, spaces and new lines included.
//                     Letters, digits, space, Enter and Tab are real key presses; the rest is pasted (works for any language)
//   k <combo>         press a key or shortcut, for example  k enter   k backspace   k ctrl+c,  k alt+tab
//
// Why a live connection: with normal requests every mouse movement has to wait for the PC's answer before the
// next one can be sent. Over the internet that answer takes 100-300 ms, so only a few movements per second get
// through. A WebSocket stays open and the movements stream through without waiting.

sealed class SocketCommand {
    data class Move(val dx: Int, val dy: Int) : SocketCommand()
    data class Click(val right: Boolean) : SocketCommand()
    data class Scroll(val amount: Int) : SocketCommand()
    data class Ping(val stamp: String) : SocketCommand()
    data class View(val width: Int, val quality: Int, val zoomPercent: Int, val cx: Int, val cy: Int) : SocketCommand()
    object ViewOff : SocketCommand()
    object Ack : SocketCommand()
    data class Point(val x: Int, val y: Int) : SocketCommand()      // 0..1000 = fraction of the whole screen
    object DoubleClick : SocketCommand()
    data class Button(val down: Boolean) : SocketCommand()          // left mouse button
    data class TypeText(val text: String) : SocketCommand()
    data class KeyPress(val combo: String) : SocketCommand()
}

// Turns one message into a command. Anything unknown or malformed gives null (and is ignored).
fun parseSocketCommand(text: String): SocketCommand? {
    // "t <text>": the text is kept exactly as sent (no trimming), because spaces and new lines are part of it
    if (text.startsWith("t ")) {
        val typed = text.substring(2)
        return if (typed.isNotEmpty() && typed.length <= 1000) SocketCommand.TypeText(typed) else null
    }
    val parts = text.trim().split(' ')
    return when (parts[0]) {
        "m" -> {
            val dx = parts.getOrNull(1)?.toIntOrNull()
            val dy = parts.getOrNull(2)?.toIntOrNull()
            if (dx != null && dy != null) SocketCommand.Move(dx.coerceIn(-1000, 1000), dy.coerceIn(-1000, 1000)) else null
        }
        "c" -> when (parts.getOrNull(1)) {
            "left" -> SocketCommand.Click(right = false)
            "right" -> SocketCommand.Click(right = true)
            "double" -> SocketCommand.DoubleClick
            else -> null
        }
        "s" -> parts.getOrNull(1)?.toIntOrNull()?.let { SocketCommand.Scroll(it.coerceIn(-50, 50)) }
        "p" -> parts.getOrNull(1)
            ?.takeIf { it.length in 1..20 && it.all { c -> c.isDigit() } }
            ?.let { SocketCommand.Ping(it) }
        "k" -> parts.getOrNull(1)?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 40 }?.let { SocketCommand.KeyPress(it) }
        "a" -> SocketCommand.Ack
        "u" -> {
            val x = parts.getOrNull(1)?.toIntOrNull()
            val y = parts.getOrNull(2)?.toIntOrNull()
            if (x != null && y != null) SocketCommand.Point(x.coerceIn(0, 1000), y.coerceIn(0, 1000)) else null
        }
        "b" -> when (parts.getOrNull(1)) {
            "down" -> SocketCommand.Button(down = true)
            "up" -> SocketCommand.Button(down = false)
            else -> null
        }
        "v" -> {
            if (parts.getOrNull(1) == "off") {
                SocketCommand.ViewOff
            } else {
                val n = parts.drop(1).map { it.toIntOrNull() }
                if (n.size == 5 && n.all { it != null }) {
                    SocketCommand.View(
                        width = n[0]!!.coerceIn(320, 1920),
                        quality = n[1]!!.coerceIn(10, 90),
                        zoomPercent = n[2]!!.coerceIn(100, 400),
                        cx = n[3]!!.coerceIn(0, 1000),
                        cy = n[4]!!.coerceIn(0, 1000)
                    )
                } else null
            }
        }
        else -> null
    }
}
