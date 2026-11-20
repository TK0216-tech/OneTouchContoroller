package jp.relative.tech.one_touch_controller.platform.windows.guard

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import jp.relative.tech.one_touch_controller.domain.config.AppLanguage
import jp.relative.tech.one_touch_controller.domain.config.AppLogger
import jp.relative.tech.one_touch_controller.domain.config.StringTranslator
import jp.relative.tech.one_touch_controller.domain.security.GuardPromptState
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

internal data class GuardOverlaySnapshot(
    val typedLength: Int,
    val prompt: GuardPromptState,
    val ownerMessage: String
)

/**
 * Swing/AWT rendering for Guard mode:
 *  - full-screen click-through, capture-excluded lock overlay across all monitors
 *  - temporary top-of-screen message popup when Guard mode is off
 *
 * Completely independent of Jetpack Compose; colours, language and translations are injected.
 */
internal class GuardOverlayUi(
    private val languageProvider: () -> AppLanguage,
    private val accentRgbProvider: () -> Int,
    private val translator: StringTranslator,
    private val logger: AppLogger
) {
    private var overlay: JWindow? = null
    private var topTimer: Timer? = null
    private var popup: JWindow? = null

    private val inkColor = Color(0x0E, 0x14, 0x20)
    private val panelColor = Color(0x16, 0x1E, 0x2D, 238)
    private val fieldColor = Color(0x0E, 0x14, 0x20)
    private val lineColor = Color(0x2C, 0x38, 0x50)
    private val textColor = Color(0xE8, 0xED, 0xF7)
    private val mutedColor = Color(0x8E, 0x9B, 0xB3)
    private val dangerColor = Color(0xFF, 0x7A, 0x7A)

    private val installedFonts: Set<String> by lazy {
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun showOverlay(snapshotProvider: () -> GuardOverlaySnapshot) {
        onUiThread {
            val window = JWindow()
            window.isAlwaysOnTop = true
            window.focusableWindowState = false                  // it never takes the keyboard away from your programs
            // a blurred, darkened photo of each monitor, taken BEFORE the lock screen appears.
            // It stays still, so people at the PC never see what your phone is doing on the desktop.
            val backdrops = try {
                val robot = Robot()
                GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
                    val b = device.defaultConfiguration.bounds
                    b to blurred(robot.createScreenCapture(b))
                }
            } catch (e: Exception) {
                emptyList()
            }
            window.contentPane = LockPicture(backdrops, snapshotProvider)
            window.bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                .map { it.defaultConfiguration.bounds }
                .reduce { a, b -> a.union(b) }                   // all monitors
            try { window.opacity = 0.99f } catch (e: Exception) { }   // makes it a "layered" window (needed for click-through)
            window.isVisible = true

            val hwnd = HWND(Native.getComponentPointer(window))
            val user32 = User32.INSTANCE
            val ex = user32.GetWindowLong(hwnd, Win32GuardConstants.GWL_EXSTYLE)
            // click-through: clicks from the phone go to the programs underneath
            user32.SetWindowLong(
                hwnd,
                Win32GuardConstants.GWL_EXSTYLE,
                ex or Win32GuardConstants.WS_EX_LAYERED or
                    Win32GuardConstants.WS_EX_TRANSPARENT or
                    Win32GuardConstants.WS_EX_TOOLWINDOW or
                    Win32GuardConstants.WS_EX_NOACTIVATE or
                    Win32GuardConstants.WS_EX_TOPMOST
            )
            // hidden from screen capture: the phone sees the desktop, not the lock picture
            val hidden = try {
                User32Extra.INSTANCE.SetWindowDisplayAffinity(hwnd, Win32GuardConstants.WDA_EXCLUDEFROMCAPTURE)
            } catch (e: Throwable) {
                false
            }
            if (!hidden) {
                logger.log("  This Windows version cannot hide the lock screen from capture: the phone's Screen tab shows the lock picture")
            }

            fun keepOnTop() {
                // cover all monitors (in real pixels) and stay above every other window
                val x = user32.GetSystemMetrics(76)
                val y = user32.GetSystemMetrics(77)
                val w = user32.GetSystemMetrics(78)
                val h = user32.GetSystemMetrics(79)
                user32.SetWindowPos(
                    hwnd,
                    HWND(Pointer.createConstant(-1)),
                    x, y, w, h,
                    Win32GuardConstants.SWP_NOACTIVATE or Win32GuardConstants.SWP_SHOWWINDOW
                )
            }
            keepOnTop()
            topTimer = Timer(700) { keepOnTop(); window.repaint() }.apply { start() }
            overlay = window
        }
    }

    fun hideOverlay() {
        onUiThread {
            topTimer?.stop()
            topTimer = null
            overlay?.dispose()
            overlay = null
        }
    }

    fun repaintOverlay() {
        overlay?.repaint()
    }

    fun showPopup(text: String) {
        onUiThread {
            popup?.dispose()
            val window = JWindow()
            window.isAlwaysOnTop = true
            window.focusableWindowState = false
            window.background = Color(0, 0, 0, 0)                // see-through corners
            val width = 480
            val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
            val height = messageCardHeight(probe, text, width) + 30          // + the "click to close" line
            probe.dispose()
            val pad = 24                                                       // room for the shadow
            val panel = object : JPanel() {
                override fun paintComponent(g0: Graphics) {
                    val g = g0 as Graphics2D
                    smooth(g)
                    shadow(g, pad.toFloat(), pad.toFloat(), width.toFloat(), height.toFloat(), 22f)
                    drawMessageCard(g, pad + width / 2, pad.toFloat(), width, text, extraBottom = 30)
                    g.font = uiFont(12.5f, false)
                    g.color = mutedColor
                    val hint = translator.tr("Click to close")
                    g.drawString(hint, pad + width - g.fontMetrics.stringWidth(hint) - 22, pad + height - 16)
                }
            }
            panel.isOpaque = false
            window.contentPane = panel
            window.setSize(width + 2 * pad, height + 2 * pad)
            val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
            window.setLocation(screen.x + (screen.width - width) / 2 - pad, screen.y + 40)
            panel.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { window.dispose() }
            })
            window.isVisible = true
            Timer(20_000) { window.dispose() }.apply { isRepeats = false; start() }
            popup = window
        }
    }

    private fun onUiThread(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }

    private fun accent(): Color = Color(accentRgbProvider())

    private fun withAlpha(c: Color, a: Int) = Color(c.red, c.green, c.blue, a)

    private fun smooth(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    }

    /** A soft shadow under a rounded box (several faint layers). */
    private fun shadow(g: Graphics2D, x: Float, y: Float, w: Float, h: Float, r: Float) {
        for (i in 1..14) {
            g.color = Color(0, 0, 0, 5)
            g.fill(RoundRectangle2D.Float(x - i, y - i + 8, w + 2 * i, h + 2 * i, r + 2 * i, r + 2 * i))
        }
    }

    // Fonts that look right on Windows for each language (the first one that is installed is used)
    private fun family(light: Boolean = false): String {
        val wanted = when (languageProvider()) {
            AppLanguage.JA -> listOf("Yu Gothic UI", "Meiryo UI", "Meiryo")
            AppLanguage.ZH -> listOf("Microsoft YaHei UI", "Microsoft YaHei")
            AppLanguage.EN -> if (light) listOf("Segoe UI Light", "Segoe UI") else listOf("Segoe UI")
        }
        return wanted.firstOrNull { it in installedFonts } ?: "Dialog"
    }

    private fun uiFont(size: Float, bold: Boolean): Font = Font(family(), if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size)

    private fun clockFont(size: Float): Font {
        val light = listOf("Segoe UI Light", "Segoe UI Semilight").firstOrNull { it in installedFonts }
        return Font(light ?: family(), Font.PLAIN, 1).deriveFont(size)
    }

    private fun wrapLines(g: Graphics2D, text: String, maxWidth: Int, max: Int = 4): List<String> {
        val fm = g.fontMetrics
        val lines = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
            var line = ""
            val pieces = if (paragraph.contains(' ')) paragraph.split(' ').map { "$it " } else paragraph.map { it.toString() }
            for (piece in pieces) {
                if (line.isNotEmpty() && fm.stringWidth((line + piece).trimEnd()) > maxWidth) {
                    lines.add(line.trimEnd())
                    line = ""
                }
                line += piece
            }
            lines.add(line.trimEnd())
        }
        if (lines.size > max) return lines.take(max - 1) + (lines[max - 1].dropLast(1) + "…")
        return lines
    }

    private fun messageCardHeight(g: Graphics2D, text: String, width: Int): Int {
        g.font = uiFont(19f, true)
        return 62 + wrapLines(g, text, width - 48).size * 28 + 6
    }

    /** The "Message from the owner's phone" card: centred at [cx], starting at [top]. Returns its height. */
    private fun drawMessageCard(g: Graphics2D, cx: Int, top: Float, width: Int, text: String, extraBottom: Int = 0): Int {
        val accent = accent()
        val h = messageCardHeight(g, text, width) + extraBottom
        val x = cx - width / 2f
        val box = RoundRectangle2D.Float(x, top, width.toFloat(), h.toFloat(), 22f, 22f)
        g.color = panelColor
        g.fill(box)
        g.color = withAlpha(accent, 150)
        g.stroke = BasicStroke(1.5f)
        g.draw(box)
        // label: a small phone + "Message from the owner's phone"
        g.color = accent
        g.stroke = BasicStroke(1.8f)
        g.draw(RoundRectangle2D.Float(x + 24f, top + 18f, 11f, 18f, 4f, 4f))
        g.fillOval((x + 28f).toInt(), (top + 31f).toInt(), 3, 3)
        g.font = uiFont(13f, true)
        g.drawString(translator.tr("Message from the owner's phone"), x + 44f, top + 32f)
        g.font = uiFont(19f, true)
        g.color = textColor
        var y = top + 66f
        for (line in wrapLines(g, text, width - 48)) {
            g.drawString(line, x + 24f, y)
            y += 28f
        }
        return h
    }

    /**
     * Makes a smooth, strongly blurred copy of a screen picture: it is shrunk step by step (which already blurs),
     * blurred again with soft edges, and enlarged step by step. The drawing stretches it to full size.
     */
    private fun blurred(src: BufferedImage): BufferedImage {
        fun resize(img: BufferedImage, w: Int, h: Int): BufferedImage {
            val out = BufferedImage(maxOf(1, w), maxOf(1, h), BufferedImage.TYPE_INT_RGB)
            out.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(img, 0, 0, out.width, out.height, null)
                dispose()
            }
            return out
        }
        var img = resize(src, src.width, src.height)
        while (img.width > src.width / 14 && img.width > 40) img = resize(img, img.width / 2, img.height / 2)
        img = boxBlur(boxBlur(boxBlur(img, 2), 2), 2)
        while (img.width < src.width / 4) img = resize(img, img.width * 2, img.height * 2)
        return img
    }

    /** Box blur with the edges repeated outwards, so no dark or sharp borders appear. */
    private fun boxBlur(img: BufferedImage, r: Int): BufferedImage {
        val w = img.width
        val h = img.height
        val src = img.getRGB(0, 0, w, h, null, 0, w)
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        val n = 2 * r + 1
        fun pass(from: IntArray, to: IntArray, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            for (l in 0 until lines) {
                for (i in 0 until len) {
                    var rs = 0
                    var gs = 0
                    var bs = 0
                    for (k in -r..r) {
                        val j = (i + k).coerceIn(0, len - 1)
                        val p = if (horizontal) from[l * w + j] else from[j * w + l]
                        rs += (p shr 16) and 255
                        gs += (p shr 8) and 255
                        bs += p and 255
                    }
                    val v = (0xFF shl 24) or ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
                    if (horizontal) to[l * w + i] = v else to[i * w + l] = v
                }
            }
        }
        pass(src, tmp, true)
        pass(tmp, out, false)
        val result = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        result.setRGB(0, 0, w, h, out, 0, w)
        return result
    }

    /**
     * What the person at the PC sees, on every monitor: the blurred desktop with a dark tint,
     * a large clock at the top (like a phone lock screen), and in the middle the Guard mode card
     * in the same style as the PC window. A message from the phone appears above the card.
     */
    private inner class LockPicture(
        private val backdrops: List<Pair<Rectangle, BufferedImage>>,
        private val snapshotProvider: () -> GuardOverlaySnapshot
    ) : JPanel() {
        override fun paintComponent(g0: Graphics) {
            val g = g0 as Graphics2D
            smooth(g)
            val origin = try { locationOnScreen.let { it.x to it.y } } catch (e: Exception) { 0 to 0 }

            // background: the blurred desktop (or plain navy if the picture could not be taken)
            g.color = inkColor
            g.fillRect(0, 0, width, height)
            for ((b, image) in backdrops) {
                g.drawImage(image, b.x - origin.first, b.y - origin.second, b.width, b.height, null)
            }
            g.color = Color(0x0E, 0x14, 0x20, 150)              // navy tint, the colour of the app
            g.fillRect(0, 0, width, height)

            val screens = backdrops.map { it.first }.ifEmpty {
                GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
            }
            val snapshot = snapshotProvider()
            for (b in screens) {
                val sx = b.x - origin.first
                val sy = b.y - origin.second
                // a little darker towards the bottom, so the card stands out
                g.paint = GradientPaint(0f, sy + b.height * 0.45f, Color(0, 0, 0, 0), 0f, (sy + b.height).toFloat(), Color(0, 0, 0, 90))
                g.fillRect(sx, sy, b.width, b.height)
                drawScreen(g, sx, sy, b.width, b.height, snapshot)
            }
        }

        private fun centered(g: Graphics2D, text: String, cx: Int, baseline: Float, f: Font, color: Color) {
            g.font = f
            g.color = color
            g.drawString(text, cx - g.fontMetrics.stringWidth(text) / 2f, baseline)
        }

        private fun drawScreen(g: Graphics2D, sx: Int, sy: Int, sw: Int, sh: Int, snapshot: GuardOverlaySnapshot) {
            val cx = sx + sw / 2
            val accent = accent()
            val lang = languageProvider()

            // ----- clock and date -----
            val now = LocalDateTime.now()
            val clockTop = sy + sh * 0.11f
            centered(g, "%d:%02d".format(now.hour, now.minute), cx, clockTop + 92f, clockFont(104f), textColor)
            val locale = when (lang) {
                AppLanguage.JA -> Locale.JAPANESE
                AppLanguage.ZH -> Locale.CHINESE
                AppLanguage.EN -> Locale.ENGLISH
            }
            val datePattern = when (lang) {
                AppLanguage.JA, AppLanguage.ZH -> "M月d日 EEEE"
                AppLanguage.EN -> "EEEE, MMMM d"
            }
            val date = now.format(DateTimeFormatter.ofPattern(datePattern, locale))
            centered(g, date, cx, clockTop + 134f, uiFont(22f, false), withAlpha(textColor, 210))
            val clockBottom = clockTop + 160f

            // ----- sizes of the card -----
            val cardW = 440
            g.font = uiFont(14.5f, false)
            val sub = wrapLines(g, translator.tr("It is being used remotely with One Touch Controller."), cardW - 64, 2) +
                wrapLines(g, translator.tr("Type the Guard password and press Enter."), cardW - 64, 2)
            val noteText = when (val prompt = snapshot.prompt) {
                GuardPromptState.None -> ""
                GuardPromptState.Checking -> translator.tr("Checking...")
                GuardPromptState.WrongPassword -> translator.tr("Wrong password")
                is GuardPromptState.TooManyWrong -> translator.tr("Too many wrong passwords. Wait %1 seconds.", prompt.waitSeconds)
            }
            val cardH = 28 + 60 + 18 + 30 + sub.size * 21 + 22 + 50 + (if (noteText.isNotEmpty()) 34 else 0) + 28
            val owner = snapshot.ownerMessage
            val msgW = cardW
            val msgH = if (owner.isNotEmpty()) messageCardHeight(g, owner, msgW) else 0
            val gap = 16
            var top = sy + sh * 0.56f - cardH / 2f
            val minTop = clockBottom + 24f + (if (msgH > 0) msgH + gap else 0)
            if (top < minTop) top = minTop

            // ----- message from the phone (above the card) -----
            if (owner.isNotEmpty()) {
                val my = top - gap - msgH
                shadow(g, cx - msgW / 2f, my, msgW.toFloat(), msgH.toFloat(), 22f)
                drawMessageCard(g, cx, my, msgW, owner)
            }

            // ----- the card -----
            val x = cx - cardW / 2f
            shadow(g, x, top, cardW.toFloat(), cardH.toFloat(), 24f)
            val card = RoundRectangle2D.Float(x, top, cardW.toFloat(), cardH.toFloat(), 24f, 24f)
            g.color = panelColor
            g.fill(card)
            g.color = lineColor
            g.stroke = BasicStroke(1f)
            g.draw(card)

            // the app's rounded tile, with a lock
            var y = top + 28f
            val tile = 60f
            g.color = accent
            g.fill(RoundRectangle2D.Float(cx - tile / 2, y, tile, tile, 18f, 18f))
            g.color = Color(0x05, 0x25, 0x21)
            g.stroke = BasicStroke(3.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawArc(cx - 9, (y + 13).toInt(), 18, 20, 0, 180)
            g.drawLine(cx - 9, (y + 23).toInt(), cx - 9, (y + 28).toInt())
            g.drawLine(cx + 9, (y + 23).toInt(), cx + 9, (y + 28).toInt())
            g.fill(RoundRectangle2D.Float(cx - 13f, y + 26f, 26f, 20f, 6f, 6f))
            g.color = accent
            g.fillOval(cx - 3, (y + 32).toInt(), 6, 6)
            y += tile + 18f

            centered(g, translator.tr("Guard mode is on"), cx, y + 22f, uiFont(23f, true), textColor)
            y += 30f
            for (line in sub) {
                y += 21f
                centered(g, line, cx, y, uiFont(14.5f, false), mutedColor)
            }
            y += 22f

            // password field, like the text boxes of the app
            val fieldW = cardW - 64f
            val fieldH = 50f
            val fx = cx - fieldW / 2
            val typing = snapshot.typedLength > 0
            if (typing) {                                                   // soft accent glow while typing
                g.color = withAlpha(accent, 45)
                g.fill(RoundRectangle2D.Float(fx - 4, y - 4, fieldW + 8, fieldH + 8, 18f, 18f))
            }
            val field = RoundRectangle2D.Float(fx, y, fieldW, fieldH, 14f, 14f)
            g.color = fieldColor
            g.fill(field)
            g.color = if (typing) accent else lineColor
            g.stroke = BasicStroke(if (typing) 1.8f else 1f)
            g.draw(field)
            val n = snapshot.typedLength
            if (n == 0) {
                centered(g, translator.tr("Guard password"), cx, y + 31f, uiFont(15f, false), withAlpha(mutedColor, 200))
            } else {
                val room = fieldW - 40f
                val step = minOf(20f, room / n)
                val dot = minOf(10f, step - 3f).coerceAtLeast(4f)
                val start = cx - (step * (n - 1)) / 2
                g.color = textColor
                for (i in 0 until n) g.fill(Ellipse2D.Float(start + i * step - dot / 2, y + fieldH / 2 - dot / 2, dot, dot))
            }
            y += fieldH

            // "Wrong password", "Checking..." under the field (inside the card)
            if (noteText.isNotEmpty()) {
                val checking = snapshot.prompt == GuardPromptState.Checking
                centered(g, noteText, cx, y + 26f, uiFont(14f, !checking), if (checking) mutedColor else dangerColor)
            }

            // small brand line under the card
            centered(g, "One Touch Controller", cx, top + cardH + 34f, uiFont(13f, false), withAlpha(textColor, 140))
        }
    }
}
