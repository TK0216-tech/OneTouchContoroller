// Presentation mode: a small notification in the bottom-right corner of the PC screen for every action that
// comes from a phone ("Phone: Open Notepad"), so an audience watching the PC screen sees what the phone did.
// Switched on and off in the PC window (Home tab). Off when the program starts.

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

/** The name shown for a phone command (translated with tr). */
val commandNames = mapOf(
    "open_notepad" to "Open Notepad", "open_calculator" to "Open Calculator", "open_explorer" to "Open File Explorer",
    "open_chrome" to "Open Chrome", "open_youtube" to "Open YouTube", "open_google" to "Open Google",
    "run_script_1" to "Run script 1", "volume_up" to "Volume up", "volume_down" to "Volume down", "mute" to "Mute",
    "play_pause" to "Play / Pause", "next_track" to "Next track", "prev_track" to "Previous track",
    "lock_screen" to "Lock with Windows", "screenshot" to "Screenshot", "shutdown_60" to "Shut down in 60 s",
    "restart_60" to "Restart in 60 s", "cancel_shutdown" to "Cancel shutdown"
)

/**
 * Keeps the last notifications. The same text again within 1.5 seconds does not make a new one: the old one
 * counts up instead ("Volume up ×3"). Pure logic, so it can be tested.
 */
class ToastQueue(private val max: Int = 4, private val mergeMs: Long = 1500) {
    class Toast(val text: String, var count: Int, var shownAt: Long)

    val items = ArrayList<Toast>()

    /** Returns true if a new notification was added (false = an old one counted up). */
    fun add(text: String, now: Long): Boolean {
        val last = items.lastOrNull()
        if (last != null && last.text == text && now - last.shownAt < mergeMs) {
            last.count++
            last.shownAt = now
            return false
        }
        items.add(Toast(text, 1, now))
        while (items.size > max) items.removeAt(0)
        return true
    }

    fun expire(now: Long, lifeMs: Long) { items.removeIf { now - it.shownAt > lifeMs } }
}

object Presenter {
    private const val LIFE_MS = 3500L

    /** The switch in the PC window. */
    var enabled by mutableStateOf(false)

    private val queue = ToastQueue()
    private var window: JWindow? = null
    private var timer: Timer? = null

    /** Shows "Phone: <text>" if presentation mode is on. Safe to call from any thread. */
    fun show(text: String) {
        if (!enabled) return
        SwingUtilities.invokeLater {
            synchronized(queue) { queue.add(text, System.currentTimeMillis()) }
            refresh()
        }
    }

    private fun refresh() {
        val items = synchronized(queue) {
            queue.expire(System.currentTimeMillis(), LIFE_MS)
            queue.items.map { it.text to it.count }
        }
        if (items.isEmpty() || !enabled) {
            timer?.stop(); timer = null
            window?.dispose(); window = null
            return
        }
        val w = window ?: JWindow().also { win ->
            win.isAlwaysOnTop = true
            win.focusableWindowState = false
            win.background = Color(0, 0, 0, 0)
            win.contentPane = ToastPanel().apply { isOpaque = false }
            window = win
        }
        (w.contentPane as ToastPanel).items = items
        val rowH = 64
        val gap = 10
        val width = 400
        val height = items.size * (rowH + gap) + 24
        // bottom-right corner, above the taskbar
        val usable = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        w.setBounds(usable.x + usable.width - width - 16, usable.y + usable.height - height - 8, width, height)
        if (!w.isVisible) w.isVisible = true
        w.repaint()
        if (timer == null) timer = Timer(300) { refresh() }.apply { start() }
    }

    private class ToastPanel : JPanel() {
        var items: List<Pair<String, Int>> = emptyList()

        override fun paintComponent(g0: Graphics) {
            val g = g0 as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val accent = OtcAccent.let { Color(it.red, it.green, it.blue) }
            val family = when (Look.lang) { "ja" -> "Yu Gothic UI"; "zh" -> "Microsoft YaHei UI"; else -> "Segoe UI" }
            val rowH = 64
            val gap = 10
            var y = height - rowH - 12
            for ((text, count) in items.asReversed()) {          // newest at the bottom
                val box = RoundRectangle2D.Float(12f, y.toFloat(), width - 24f, rowH.toFloat(), 18f, 18f)
                for (i in 1..6) {                                 // soft shadow
                    g.color = Color(0, 0, 0, 10)
                    g.fill(RoundRectangle2D.Float(12f - i, y - i + 4f, width - 24f + 2 * i, rowH + 2f * i, 18f + 2 * i, 18f + 2 * i))
                }
                g.color = Color(0x16, 0x1E, 0x2D, 242)
                g.fill(box)
                g.color = Color(0x2C, 0x38, 0x50)
                g.stroke = BasicStroke(1f)
                g.draw(box)
                // phone badge in the accent colour
                g.color = accent
                g.fill(RoundRectangle2D.Float(26f, y + 14f, 36f, 36f, 11f, 11f))
                g.color = Color(0x05, 0x25, 0x21)
                g.stroke = BasicStroke(2.2f)
                g.draw(RoundRectangle2D.Float(37f, y + 21f, 14f, 22f, 4f, 4f))
                g.fillOval(42, y + 37, 4, 4)
                // "Phone" label + the action
                g.font = Font(family, Font.BOLD, 1).deriveFont(12f)
                g.color = accent
                g.drawString(tr("From the phone"), 76f, y + 26f)
                g.font = Font(family, Font.BOLD, 1).deriveFont(17f)
                g.color = Color(0xE8, 0xED, 0xF7)
                val shown = (if (count > 1) "$text  ×$count" else text).let { if (it.length > 34) it.take(33) + "…" else it }
                g.drawString(shown, 76f, y + 48f)
                y -= rowH + gap
            }
        }
    }
}
