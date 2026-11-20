// Guard mode: protects the PC while you control it from your phone and are not sitting in front of it.
//
// While Guard mode is on:
//  - the PC's own mouse and keyboard do nothing. Your phone still works, because Windows marks the
//    input that this program creates ("injected" input) differently from a real mouse or keyboard,
//    and only the real ones are blocked.
//  - a full-screen lock picture covers the monitor. It is hidden from screen capture, so your phone
//    still sees the normal desktop (Windows 10 version 2004 or newer, or Windows 11).
//  - someone at the PC can only type the Guard password and press Enter. 5 wrong tries = 1 minute wait.
//  - shutting down from the Start menu is impossible (mouse and keyboard are blocked), Windows asks before a
//    shutdown from another place, the power, sleep and lid buttons are set to "do nothing", and the PC does not go to sleep.
//
// What NO program can block (Windows does not allow it):
//  - Ctrl+Alt+Del (from there someone could sign out or shut down; signing out ends Guard mode)
//  - holding the power button for 4 seconds, or pulling the plug
//
// Safety: if this program freezes or closes, Windows removes the block by itself, so you can never be locked out.
// If Windows locks itself (power button, Win+L, Ctrl+Alt+Del > Lock, sleep), Guard mode turns itself off at once:
// the Windows password protects the PC from then on, and you must be able to type it.
// You can also always turn Guard mode off from your phone.

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.platform.win32.Wtsapi32
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.Rectangle
import java.awt.Robot
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

// =====================================================================================
// Part 1: plain logic (no Windows calls, so it can be tested anywhere)
// =====================================================================================

object GuardPassword {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 32

    /** Passwords use letters and numbers only and do not care about capital letters ("abc1" = "ABC1"). */
    fun normalize(text: String): String = text.trim().uppercase()

    fun isValid(text: String): Boolean {
        val p = normalize(text)
        return p.length in MIN_LENGTH..MAX_LENGTH && p.all { it in 'A'..'Z' || it in '0'..'9' }
    }

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    /** Slow on purpose (PBKDF2), so a stolen settings file does not reveal the password quickly. */
    fun hash(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(normalize(password).toCharArray(), salt, 120_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun matches(password: String, salt: ByteArray, expected: ByteArray): Boolean =
        MessageDigest.isEqual(hash(password, salt), expected)
}

/** Windows key code -> the character it types in the password box (letters, top-row digits, number pad). */
fun guardKeyChar(vk: Int): Char? = when (vk) {
    in 0x30..0x39 -> '0' + (vk - 0x30)
    in 0x41..0x5A -> 'A' + (vk - 0x41)
    in 0x60..0x69 -> '0' + (vk - 0x60)
    else -> null
}

/** Counts wrong passwords typed at the PC. Every 5th wrong one makes the person wait a minute. */
class GuardAttempts(private val waitMs: Long = 60_000) {
    @Volatile var wrong = 0
        private set
    @Volatile var lockedUntil = 0L
        private set

    fun secondsLeft(now: Long): Long = if (lockedUntil > now) (lockedUntil - now + 999) / 1000 else 0

    /** Returns true if this wrong password started a wait. */
    fun recordWrong(now: Long): Boolean {
        wrong++
        if (wrong % 5 == 0) { lockedUntil = now + waitMs; return true }
        return false
    }

    fun reset() { wrong = 0; lockedUntil = 0 }
}

/**
 * Reads  powercfg /query  output. The language of Windows does not matter: the last two lines that end
 * with a value like ": 0x00000001" are "plugged in" (AC) and "on battery" (DC). Returns null if not found.
 */
fun parsePowercfgValues(output: String): Pair<Int, Int>? {
    val values = output.lines().mapNotNull { line ->
        Regex(":\\s*0x([0-9a-fA-F]+)\\s*$").find(line.trim())?.groupValues?.get(1)?.toLongOrNull(16)?.toInt()
    }
    if (values.size < 2) return null
    return values[values.size - 2] to values[values.size - 1]
}

// =====================================================================================
// Part 2: the Windows functions JNA does not list by itself
// =====================================================================================

private interface User32Extra : StdCallLibrary {
    fun SetWindowDisplayAffinity(hWnd: HWND, affinity: Int): Boolean
    fun ShutdownBlockReasonCreate(hWnd: HWND, reason: WString): Boolean
    fun ShutdownBlockReasonDestroy(hWnd: HWND): Boolean

    companion object {
        val INSTANCE: User32Extra by lazy { Native.load("user32", User32Extra::class.java, W32APIOptions.DEFAULT_OPTIONS) }
    }
}

private const val WM_QUIT = 0x0012
private const val WM_QUERYENDSESSION = 0x0011
private const val WM_WTSSESSION_CHANGE = 0x02B1     // Windows was locked / unlocked
private const val WM_POWERBROADCAST = 0x0218         // the PC is going to sleep
private const val PBT_APMSUSPEND = 0x0004
private const val WM_KEYDOWN = 0x0100
private const val WM_SYSKEYDOWN = 0x0104
private const val LLKHF_INJECTED = 0x10 or 0x02          // made by a program (+ "from a lower level program")
private const val LLMHF_INJECTED = 0x01 or 0x02
private const val ES_CONTINUOUS = 0x80000000.toInt()
private const val ES_SYSTEM_REQUIRED = 0x00000001
private const val ES_DISPLAY_REQUIRED = 0x00000002
private const val WS_EX_LAYERED = 0x00080000
private const val WS_EX_TRANSPARENT = 0x00000020
private const val WS_EX_TOOLWINDOW = 0x00000080
private const val WS_EX_NOACTIVATE = 0x08000000
private const val WS_EX_TOPMOST = 0x00000008
private const val GWL_EXSTYLE = -20
private const val WDA_EXCLUDEFROMCAPTURE = 0x11
private const val SWP_NOACTIVATE = 0x0010
private const val SWP_SHOWWINDOW = 0x0040

// =====================================================================================
// Part 3: Guard mode itself
// =====================================================================================

object GuardMode {
    private val file = File(System.getProperty("user.home"), ".onetouch/guard.properties")

    /** On or off. The PC window and the phone read this. */
    var active by mutableStateOf(false)
        private set

    /** Is a Guard password saved? (Guard mode cannot be turned on without one.) */
    var hasPassword by mutableStateOf(false)
        private set

    val attempts = GuardAttempts()

    @Volatile private var blocking = false               // true = real mouse and keyboard are blocked
    @Volatile private var typed = ""                       // what the person at the PC has typed so far
    @Volatile private var message = ""                     // "Wrong password" etc. on the lock picture
    @Volatile private var ownerMessage = ""                // a message sent from the owner's phone (shown above the card)
    private var popup: JWindow? = null                     // the message window when Guard mode is off
    @Volatile private var hookThreadId = 0
    private var hookThread: Thread? = null
    private var overlay: JWindow? = null
    private var topTimer: Timer? = null
    private var salt: ByteArray? = null
    private var hash: ByteArray? = null
    private val checker = Executors.newSingleThreadExecutor { Thread(it, "guard-password").apply { isDaemon = true } }
    private val powerSettings = listOf("PBUTTONACTION", "SBUTTONACTION", "LIDACTION")

    // The hooks must stay referenced, or Java would throw them away while Windows still calls them
    private var keyboardHook: WinUser.HHOOK? = null
    private var mouseHook: WinUser.HHOOK? = null
    private var keyboardProc: WinUser.LowLevelKeyboardProc? = null
    private var mouseProc: WinUser.LowLevelMouseProc? = null
    private var shutdownWindowProc: WinUser.WindowProc? = null

    val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("windows")

    // ---------- settings file ----------
    private fun readFile(): Properties = Properties().also { p ->
        try { if (file.isFile) file.inputStream().use { p.load(it) } } catch (e: Exception) { }
    }

    private fun writeFile(p: Properties) {
        file.parentFile?.mkdirs()
        file.outputStream().use { p.store(it, "One Touch Controller - Guard mode (the password is stored as a hash)") }
    }

    /** Call once when the program starts. Also repairs the power buttons if the program was closed while guarding. */
    fun load() {
        val p = readFile()
        try {
            salt = p.getProperty("salt")?.let { Base64.getDecoder().decode(it) }
            hash = p.getProperty("hash")?.let { Base64.getDecoder().decode(it) }
        } catch (e: Exception) {
            salt = null; hash = null
        }
        hasPassword = salt != null && hash != null
        if (isWindows && powerSettings.any { p.getProperty("power.$it") != null }) {
            Thread { restorePowerButtons() }.start()
        }
        // if the program is closed while guarding, the power buttons are put back
        Runtime.getRuntime().addShutdownHook(Thread { if (active) try { restorePowerButtons() } catch (e: Exception) { } })
    }

    /** Saves a new Guard password. Returns an error message, or null if it was saved. */
    fun setPassword(text: String): String? {
        if (!GuardPassword.isValid(text)) return tr("Use 4 to 32 letters and numbers only.")
        val newSalt = GuardPassword.newSalt()
        val newHash = GuardPassword.hash(text, newSalt)
        val p = readFile()
        p.setProperty("salt", Base64.getEncoder().encodeToString(newSalt))
        p.setProperty("hash", Base64.getEncoder().encodeToString(newHash))
        writeFile(p)
        salt = newSalt; hash = newHash; hasPassword = true
        return null
    }

    // ---------- on / off ----------
    /** Turns Guard mode on. Throws an exception with a readable message if that is not possible. */
    @Synchronized
    fun turnOn(who: String) {
        if (active) return
        if (!isWindows) throw IllegalStateException("Guard mode works on Windows only")
        if (!hasPassword) throw IllegalStateException("Set a guard password in the PC window first")
        startHooks()
        try {
            onUiThread { showOverlay() }
        } catch (e: Exception) {
            stopHooks()
            throw IllegalStateException("could not show the lock screen: ${e.message}")
        }
        typed = ""
        message = ""
        ownerMessage = ""
        attempts.reset()
        endedByWindows = false
        blocking = true
        active = true
        appLog("Guard mode ON ($who): the PC's own mouse and keyboard are blocked")
        startLockWatch()
        Thread { try { savePowerButtonsAndDisable() } catch (e: Exception) { appLog("  Could not change the power button: ${e.message}") } }.start()
    }

    @Synchronized
    fun turnOff(who: String) {
        if (!active) return
        blocking = false
        stopHooks()
        try { onUiThread { hideOverlay() } } catch (e: Exception) { }
        active = false
        typed = ""
        ownerMessage = ""
        appLog("Guard mode OFF ($who)")
        Thread { try { restorePowerButtons() } catch (e: Exception) { } }.start()
    }

    // ---------- messages from the phone ----------
    /**
     * Shows a message from the phone on this PC: on the lock screen while Guard mode is on,
     * otherwise in a small window at the top of the screen (closes with a click or after 20 seconds).
     */
    fun showMessage(text: String) {
        val clean = text.replace(Regex("[\\p{Cntrl}&&[^\\n]]"), "").trim().take(200)
        if (clean.isEmpty()) return
        appLog("Message from the phone shown on the PC: $clean")
        if (active) {
            ownerMessage = clean
            overlay?.repaint()
        } else {
            onUiThread { showPopup(clean) }
        }
    }

    private fun showPopup(text: String) {
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
                g.color = MUTED
                val hint = tr("Click to close")
                g.drawString(hint, pad + width - g.fontMetrics.stringWidth(hint) - 22, pad + height - 16)
            }
        }
        panel.isOpaque = false
        window.contentPane = panel
        window.setSize(width + 2 * pad, height + 2 * pad)
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
        window.setLocation(screen.x + (screen.width - width) / 2 - pad, screen.y + 40)
        panel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { window.dispose() }
        })
        window.isVisible = true
        Timer(20_000) { window.dispose() }.apply { isRepeats = false; start() }
        popup = window
    }

    // ---------- the look: the same colours as the PC window and the phone apps ----------
    private val INK = Color(0x0E, 0x14, 0x20)
    private val PANEL = Color(0x16, 0x1E, 0x2D, 238)
    private val FIELD = Color(0x0E, 0x14, 0x20)
    private val LINE = Color(0x2C, 0x38, 0x50)
    private val TEXT = Color(0xE8, 0xED, 0xF7)
    private val MUTED = Color(0x8E, 0x9B, 0xB3)
    private val DANGER = Color(0xFF, 0x7A, 0x7A)
    private fun accent(): Color = OtcAccent.let { Color(it.red, it.green, it.blue) }      // from Settings
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
    private val installedFonts: Set<String> by lazy {
        try { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet() } catch (e: Exception) { emptySet() }
    }
    private fun family(light: Boolean = false): String {
        val wanted = when (Look.lang) {
            "ja" -> listOf("Yu Gothic UI", "Meiryo UI", "Meiryo")
            "zh" -> listOf("Microsoft YaHei UI", "Microsoft YaHei")
            else -> if (light) listOf("Segoe UI Light", "Segoe UI") else listOf("Segoe UI")
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
                if (line.isNotEmpty() && fm.stringWidth((line + piece).trimEnd()) > maxWidth) { lines.add(line.trimEnd()); line = "" }
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
        g.color = PANEL
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
        g.drawString(tr("Message from the owner's phone"), x + 44f, top + 32f)
        g.font = uiFont(19f, true)
        g.color = TEXT
        var y = top + 66f
        for (line in wrapLines(g, text, width - 48)) {
            g.drawString(line, x + 24f, y)
            y += 28f
        }
        return h
    }

    /** Set when Guard mode turned itself off because Windows locked or the PC went to sleep (shown on the phone once). */
    @Volatile var endedByWindows = false

    /** For the phone: "off", "off locked" (Windows locked the PC), "nopassword", or "on <wrong passwords typed at the PC>" */
    fun statusText(): String = when {
        active -> "on ${attempts.wrong}"
        !hasPassword -> "nopassword"
        endedByWindows -> "off locked"
        else -> "off"
    }

    /**
     * Windows locked the PC or is going to sleep. Called from inside the Windows message loop, so the block is
     * lifted immediately (the person must be able to type the Windows password) and the rest is done on another thread.
     */
    private fun windowsTookOver(what: String) {
        if (!blocking) return
        blocking = false
        endedByWindows = true
        Thread { turnOff("$what - the Windows password protects the PC now") }.start()
    }

    private fun onUiThread(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }

    /**
     * Backup check every 2 seconds: is Windows locked? (Normally Windows tells us at once, see the hidden window.
     * This catches the case where that message is missed, so the lock screen can never stay blocked.)
     */
    private fun startLockWatch() {
        Thread({
            // The check must say "unlocked" right now (you just turned Guard mode on at an unlocked PC).
            // If it does not, it cannot be trusted on this PC, and only Windows' own lock message is used.
            if (lockState() != 1) {
                appLog("  (The backup lock check does not work on this PC; Guard mode uses Windows' lock message only)")
                return@Thread
            }
            var lockedReadings = 0
            while (active && blocking) {
                lockedReadings = if (lockState() == 0) lockedReadings + 1 else 0
                if (lockedReadings >= 2) {                       // "locked" twice in a row, 2 seconds apart
                    windowsTookOver("Windows locked the PC")
                    return@Thread
                }
                try { Thread.sleep(2000) } catch (e: InterruptedException) { return@Thread }
            }
        }, "guard-lock-watch").apply { isDaemon = true }.start()
    }

    /** 0 = Windows is locked, 1 = unlocked, null = could not tell. */
    private fun lockState(): Int? = try {
        val wts = Wtsapi32.INSTANCE
        val buffer = PointerByReference()
        if (!wts.WTSQuerySessionInformation(Wtsapi32.WTS_CURRENT_SERVER_HANDLE, Wtsapi32.WTS_CURRENT_SESSION,
                Wtsapi32.WTS_INFO_CLASS.WTSSessionInfoEx, buffer, com.sun.jna.ptr.IntByReference())) {
            null
        } else {
            // WTSINFOEX: Level (4 bytes) + 4 bytes padding (the data part starts at 8 because it holds 8-byte numbers),
            // then SessionId (4), SessionState (4), SessionFlags (4) -> SessionFlags is at byte 16.
            val level = buffer.value.getInt(0)
            val flags = buffer.value.getInt(16)
            wts.WTSFreeMemory(buffer.value)
            if (level == 1 && (flags == 0 || flags == 1)) flags else null
        }
    } catch (e: Throwable) {
        null
    }

    // ---------- the person at the PC typing the password (runs inside the keyboard hook: must be quick) ----------
    private fun physicalKey(vk: Int) {
        val now = System.currentTimeMillis()
        val wait = attempts.secondsLeft(now)
        if (wait > 0) {
            message = tr("Too many wrong passwords. Wait %1 seconds.", wait)
        } else when (vk) {
            0x08 -> typed = typed.dropLast(1)                        // Backspace
            0x1B -> typed = ""                                       // Esc
            0x0D -> {                                                // Enter: check it on another thread (it is slow on purpose)
                val attempt = typed
                typed = ""
                message = tr("Checking...")
                checker.execute { checkPassword(attempt) }
            }
            else -> guardKeyChar(vk)?.let { if (typed.length < GuardPassword.MAX_LENGTH) { typed += it; message = "" } }
        }
        overlay?.repaint()
    }

    private fun checkPassword(attempt: String) {
        val s = salt
        val h = hash
        if (attempt.isNotEmpty() && s != null && h != null && GuardPassword.matches(attempt, s, h)) {
            turnOff("unlocked with the password at the PC")
            return
        }
        val waitStarted = attempts.recordWrong(System.currentTimeMillis())
        appLog("Guard mode: someone typed a wrong password at the PC (${attempts.wrong} so far)")
        message = if (waitStarted) tr("Too many wrong passwords. Wait %1 seconds.", 60) else tr("Wrong password")
        overlay?.repaint()
    }

    // ---------- blocking the real mouse and keyboard ----------
    private fun startHooks() {
        val started = CountDownLatch(1)
        var problem: String? = null
        val thread = Thread({
            val user32 = User32.INSTANCE
            val kernel32 = Kernel32.INSTANCE
            hookThreadId = kernel32.GetCurrentThreadId()
            val module = kernel32.GetModuleHandle(null)

            keyboardProc = object : WinUser.LowLevelKeyboardProc {
                override fun callback(nCode: Int, wParam: WPARAM, info: WinUser.KBDLLHOOKSTRUCT): LRESULT {
                    if (nCode >= 0 && blocking && (info.flags and LLKHF_INJECTED) == 0) {
                        val msg = wParam.toInt()
                        if (msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN) physicalKey(info.vkCode)
                        return LRESULT(1)                                        // swallow the real key
                    }
                    return user32.CallNextHookEx(keyboardHook, nCode, wParam, LPARAM(Pointer.nativeValue(info.pointer)))
                }
            }
            mouseProc = object : WinUser.LowLevelMouseProc {
                override fun callback(nCode: Int, wParam: WPARAM, info: WinUser.MSLLHOOKSTRUCT): LRESULT {
                    if (nCode >= 0 && blocking && (info.flags and LLMHF_INJECTED) == 0) {
                        return LRESULT(1)                                        // swallow the real mouse
                    }
                    return user32.CallNextHookEx(mouseHook, nCode, wParam, LPARAM(Pointer.nativeValue(info.pointer)))
                }
            }
            keyboardHook = user32.SetWindowsHookEx(WinUser.WH_KEYBOARD_LL, keyboardProc, module, 0)
            mouseHook = user32.SetWindowsHookEx(WinUser.WH_MOUSE_LL, mouseProc, module, 0)
            if (keyboardHook == null || mouseHook == null) {
                problem = "Windows refused to block the mouse and keyboard (error ${kernel32.GetLastError()})"
                keyboardHook?.let { user32.UnhookWindowsHookEx(it) }
                mouseHook?.let { user32.UnhookWindowsHookEx(it) }
                keyboardHook = null; mouseHook = null
                started.countDown()
                return@Thread
            }

            // A hidden window that answers "no" when Windows asks "may I shut down?"
            val shutdownWindow = try { createShutdownBlocker(module) } catch (e: Exception) { null }
            // keep the PC (and the screen) awake while guarding
            kernel32.SetThreadExecutionState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED)
            started.countDown()

            val msg = WinUser.MSG()
            while (user32.GetMessage(msg, null, 0, 0) > 0) {
                user32.TranslateMessage(msg)
                user32.DispatchMessage(msg)
            }

            // Guard mode is over: clean up
            user32.UnhookWindowsHookEx(keyboardHook)
            user32.UnhookWindowsHookEx(mouseHook)
            keyboardHook = null; mouseHook = null
            if (shutdownWindow != null) {
                try { Wtsapi32.INSTANCE.WTSUnRegisterSessionNotification(shutdownWindow) } catch (e: Throwable) { }
                try { User32Extra.INSTANCE.ShutdownBlockReasonDestroy(shutdownWindow) } catch (e: Throwable) { }
                user32.DestroyWindow(shutdownWindow)
            }
            kernel32.SetThreadExecutionState(ES_CONTINUOUS)
        }, "guard-hooks")
        thread.isDaemon = true
        thread.start()
        if (!started.await(5, TimeUnit.SECONDS)) throw IllegalStateException("blocking the mouse and keyboard did not start")
        problem?.let { throw IllegalStateException(it) }
        hookThread = thread
    }

    private fun stopHooks() {
        val id = hookThreadId
        if (id != 0) User32.INSTANCE.PostThreadMessage(id, WM_QUIT, WPARAM(0), LPARAM(0))
        hookThread?.join(2000)
        hookThread = null
        hookThreadId = 0
    }

    private fun createShutdownBlocker(module: com.sun.jna.platform.win32.WinDef.HMODULE): HWND? {
        val user32 = User32.INSTANCE
        val className = "OneTouchGuardShutdownBlocker"
        shutdownWindowProc = object : WinUser.WindowProc {
            override fun callback(hwnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT {
                if (uMsg == WM_QUERYENDSESSION && blocking) {
                    appLog("Guard mode: a shutdown or sign-out was started at the PC and was refused")
                    return LRESULT(0)                        // "no, do not shut down"
                }
                if (uMsg == WM_WTSSESSION_CHANGE && wParam.toInt() == Wtsapi32.WTS_SESSION_LOCK) {
                    windowsTookOver("Windows locked the PC (power button, Win+L or Ctrl+Alt+Del)")
                }
                if (uMsg == WM_POWERBROADCAST && wParam.toInt() == PBT_APMSUSPEND) {
                    windowsTookOver("the PC is going to sleep")
                }
                return user32.DefWindowProc(hwnd, uMsg, wParam, lParam)
            }
        }
        val wc = WinUser.WNDCLASSEX()
        wc.hInstance = module
        wc.lpfnWndProc = shutdownWindowProc
        wc.lpszClassName = className
        user32.RegisterClassEx(wc)                           // already registered from an earlier time = fine
        val hwnd = user32.CreateWindowEx(0, className, "One Touch Controller guard", 0, 0, 0, 0, 0, null, null, module, null)
            ?: return null
        try {
            User32Extra.INSTANCE.ShutdownBlockReasonCreate(hwnd, WString("This PC is in Guard mode (One Touch Controller)."))
        } catch (e: Throwable) {
            // older Windows: the window still refuses, only without a reason text
        }
        // tell this window when Windows locks the PC
        val told = try { Wtsapi32.INSTANCE.WTSRegisterSessionNotification(hwnd, Wtsapi32.NOTIFY_FOR_THIS_SESSION) } catch (e: Throwable) { false }
        if (!told) appLog("  Guard mode cannot see when Windows locks the PC: if it locks, turn Guard mode off from your phone")
        return hwnd
    }

    // ---------- the lock picture ----------
    private fun showOverlay() {
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
        window.contentPane = LockPicture(backdrops)
        window.bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration.bounds }
            .reduce { a, b -> a.union(b) }                   // all monitors
        try { window.opacity = 0.99f } catch (e: Exception) { }   // makes it a "layered" window (needed for click-through)
        window.isVisible = true

        val hwnd = HWND(Native.getComponentPointer(window))
        val user32 = User32.INSTANCE
        val ex = user32.GetWindowLong(hwnd, GWL_EXSTYLE)
        // click-through: clicks from the phone go to the programs underneath
        user32.SetWindowLong(hwnd, GWL_EXSTYLE, ex or WS_EX_LAYERED or WS_EX_TRANSPARENT or WS_EX_TOOLWINDOW or WS_EX_NOACTIVATE or WS_EX_TOPMOST)
        // hidden from screen capture: the phone sees the desktop, not the lock picture
        val hidden = try { User32Extra.INSTANCE.SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE) } catch (e: Throwable) { false }
        if (!hidden) appLog("  This Windows version cannot hide the lock screen from capture: the phone's Screen tab shows the lock picture")

        fun keepOnTop() {
            // cover all monitors (in real pixels) and stay above every other window
            val x = user32.GetSystemMetrics(76); val y = user32.GetSystemMetrics(77)
            val w = user32.GetSystemMetrics(78); val h = user32.GetSystemMetrics(79)
            user32.SetWindowPos(hwnd, HWND(Pointer.createConstant(-1)), x, y, w, h, SWP_NOACTIVATE or SWP_SHOWWINDOW)
        }
        keepOnTop()
        topTimer = Timer(700) { keepOnTop(); window.repaint() }.apply { start() }
        overlay = window
    }

    private fun hideOverlay() {
        topTimer?.stop()
        topTimer = null
        overlay?.dispose()
        overlay = null
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
                    var rs = 0; var gs = 0; var bs = 0
                    for (k in -r..r) {
                        val j = (i + k).coerceIn(0, len - 1)
                        val p = if (horizontal) from[l * w + j] else from[j * w + l]
                        rs += (p shr 16) and 255; gs += (p shr 8) and 255; bs += p and 255
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
    private class LockPicture(private val backdrops: List<Pair<Rectangle, BufferedImage>>) : JPanel() {
        override fun paintComponent(g0: Graphics) {
            val g = g0 as Graphics2D
            smooth(g)
            val origin = try { locationOnScreen.let { it.x to it.y } } catch (e: Exception) { 0 to 0 }

            // background: the blurred desktop (or plain navy if the picture could not be taken)
            g.color = INK
            g.fillRect(0, 0, width, height)
            for ((b, image) in backdrops) {
                g.drawImage(image, b.x - origin.first, b.y - origin.second, b.width, b.height, null)
            }
            g.color = Color(0x0E, 0x14, 0x20, 150)              // navy tint, the colour of the app
            g.fillRect(0, 0, width, height)

            val screens = backdrops.map { it.first }.ifEmpty {
                GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
            }
            for (b in screens) {
                val sx = b.x - origin.first
                val sy = b.y - origin.second
                // a little darker towards the bottom, so the card stands out
                g.paint = GradientPaint(0f, sy + b.height * 0.45f, Color(0, 0, 0, 0), 0f, (sy + b.height).toFloat(), Color(0, 0, 0, 90))
                g.fillRect(sx, sy, b.width, b.height)
                drawScreen(g, sx, sy, b.width, b.height)
            }
        }

        private fun centered(g: Graphics2D, text: String, cx: Int, baseline: Float, f: Font, color: Color) {
            g.font = f
            g.color = color
            g.drawString(text, cx - g.fontMetrics.stringWidth(text) / 2f, baseline)
        }

        private fun drawScreen(g: Graphics2D, sx: Int, sy: Int, sw: Int, sh: Int) {
            val cx = sx + sw / 2
            val accent = accent()

            // ----- clock and date -----
            val now = java.time.LocalDateTime.now()
            val clockTop = sy + sh * 0.11f
            centered(g, "%d:%02d".format(now.hour, now.minute), cx, clockTop + 92f, clockFont(104f), TEXT)
            val locale = when (Look.lang) { "ja" -> java.util.Locale.JAPANESE; "zh" -> java.util.Locale.CHINESE; else -> java.util.Locale.ENGLISH }
            val datePattern = when (Look.lang) { "ja" -> "M月d日 EEEE"; "zh" -> "M月d日 EEEE"; else -> "EEEE, MMMM d" }
            val date = now.format(java.time.format.DateTimeFormatter.ofPattern(datePattern, locale))
            centered(g, date, cx, clockTop + 134f, uiFont(22f, false), withAlpha(TEXT, 210))
            val clockBottom = clockTop + 160f

            // ----- sizes of the card -----
            val cardW = 440
            g.font = uiFont(14.5f, false)
            val sub = wrapLines(g, tr("It is being used remotely with One Touch Controller."), cardW - 64, 2) +
                    wrapLines(g, tr("Type the Guard password and press Enter."), cardW - 64, 2)
            val note = message
            val cardH = 28 + 60 + 18 + 30 + sub.size * 21 + 22 + 50 + (if (note.isNotEmpty()) 34 else 0) + 28
            val owner = ownerMessage
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
            g.color = PANEL
            g.fill(card)
            g.color = LINE
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

            centered(g, tr("Guard mode is on"), cx, y + 22f, uiFont(23f, true), TEXT)
            y += 30f
            for (line in sub) { y += 21f; centered(g, line, cx, y, uiFont(14.5f, false), MUTED) }
            y += 22f

            // password field, like the text boxes of the app
            val fieldW = cardW - 64f
            val fieldH = 50f
            val fx = cx - fieldW / 2
            val typing = typed.isNotEmpty()
            if (typing) {                                                   // soft accent glow while typing
                g.color = withAlpha(accent, 45)
                g.fill(RoundRectangle2D.Float(fx - 4, y - 4, fieldW + 8, fieldH + 8, 18f, 18f))
            }
            val field = RoundRectangle2D.Float(fx, y, fieldW, fieldH, 14f, 14f)
            g.color = FIELD
            g.fill(field)
            g.color = if (typing) accent else LINE
            g.stroke = BasicStroke(if (typing) 1.8f else 1f)
            g.draw(field)
            val n = typed.length
            if (n == 0) {
                centered(g, tr("Guard password"), cx, y + 31f, uiFont(15f, false), withAlpha(MUTED, 200))
            } else {
                val room = fieldW - 40f
                val step = minOf(20f, room / n)
                val dot = minOf(10f, step - 3f).coerceAtLeast(4f)
                val start = cx - (step * (n - 1)) / 2
                g.color = TEXT
                for (i in 0 until n) g.fill(Ellipse2D.Float(start + i * step - dot / 2, y + fieldH / 2 - dot / 2, dot, dot))
            }
            y += fieldH

            // "Wrong password", "Checking..." under the field (inside the card)
            if (note.isNotEmpty()) {
                val checking = note == tr("Checking...")
                centered(g, note, cx, y + 26f, uiFont(14f, !checking), if (checking) MUTED else DANGER)
            }

            // small brand line under the card
            centered(g, "One Touch Controller", cx, top + cardH + 34f, uiFont(13f, false), withAlpha(TEXT, 140))
        }
    }

    // ---------- power, sleep and lid buttons ----------
    private fun powercfg(vararg args: String): String {
        val p = ProcessBuilder(listOf("powercfg") + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        p.waitFor(10, TimeUnit.SECONDS)
        return out
    }

    @Synchronized
    private fun savePowerButtonsAndDisable() {
        val p = readFile()
        var changed = false
        for (name in powerSettings) {
            if (p.getProperty("power.$name") == null) {          // keep the very first original value
                val values = parsePowercfgValues(powercfg("/query", "SCHEME_CURRENT", "SUB_BUTTONS", name)) ?: continue
                p.setProperty("power.$name", "${values.first},${values.second}")
            }
            powercfg("/setacvalueindex", "SCHEME_CURRENT", "SUB_BUTTONS", name, "0")     // 0 = do nothing
            powercfg("/setdcvalueindex", "SCHEME_CURRENT", "SUB_BUTTONS", name, "0")
            changed = true
        }
        writeFile(p)
        if (changed) {
            powercfg("/setactive", "SCHEME_CURRENT")
            appLog("  Power, sleep and lid buttons set to \"do nothing\" while guarding")
        }
    }

    @Synchronized
    private fun restorePowerButtons() {
        val p = readFile()
        var changed = false
        for (name in powerSettings) {
            val saved = p.getProperty("power.$name") ?: continue
            val parts = saved.split(",")
            if (parts.size == 2) {
                powercfg("/setacvalueindex", "SCHEME_CURRENT", "SUB_BUTTONS", name, parts[0])
                powercfg("/setdcvalueindex", "SCHEME_CURRENT", "SUB_BUTTONS", name, parts[1])
                changed = true
            }
            p.remove("power.$name")
        }
        writeFile(p)
        if (changed) powercfg("/setactive", "SCHEME_CURRENT")
    }
}