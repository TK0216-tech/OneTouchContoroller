// The short text messages the phone / web page sends over the fast "live connection" (WebSocket) at /ws.
//
//   auth <token>      first message after connecting (the server answers "ok" or "denied")
//   m <dx> <dy>       move the mouse by dx, dy pixels
//   c left|right      click
//   s <amount>        scroll (positive = down)
//   p <number>        ping; the server answers "q <number>" so the phone can measure the delay
//
// Why a live connection: with normal requests every mouse movement has to wait for the PC's answer before the
// next one can be sent. Over the internet that answer takes 100-300 ms, so only a few movements per second get
// through. A WebSocket stays open and the movements stream through without waiting.

sealed class SocketCommand {
    data class Move(val dx: Int, val dy: Int) : SocketCommand()
    data class Click(val right: Boolean) : SocketCommand()
    data class Scroll(val amount: Int) : SocketCommand()
    data class Ping(val stamp: String) : SocketCommand()
}

// Turns one message into a command. Anything unknown or malformed gives null (and is ignored).
fun parseSocketCommand(text: String): SocketCommand? {
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
            else -> null
        }
        "s" -> parts.getOrNull(1)?.toIntOrNull()?.let { SocketCommand.Scroll(it.coerceIn(-50, 50)) }
        "p" -> parts.getOrNull(1)
            ?.takeIf { it.length in 1..20 && it.all { c -> c.isDigit() } }
            ?.let { SocketCommand.Ping(it) }
        else -> null
    }
}
