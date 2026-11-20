package jp.relative.tech.one_touch_controller.platform.windows.input

import jp.relative.tech.one_touch_controller.domain.ports.MouseButton
import jp.relative.tech.one_touch_controller.domain.ports.PcInputPort
import jp.relative.tech.one_touch_controller.platform.windows.screen.primaryScreenBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import javax.imageio.ImageIO

/**
 * [PcInputPort] implementation backed by Java AWT [Robot] and the system clipboard.
 */
class RobotPcInput(
    private val screenshotsDir: File,
    private val robotProvider: () -> Robot
) : PcInputPort {
    private val robot: Robot get() = robotProvider()

    // Only one typing job at a time, because pasting borrows the clipboard and keystrokes must stay in order
    private val typingLock = Mutex()

    override fun moveMouseBy(dx: Int, dy: Int) {
        val p = MouseInfo.getPointerInfo().location
        robot.mouseMove(p.x + dx, p.y + dy)
    }

    override fun moveMouseToFraction(x: Int, y: Int) {
        val screen = primaryScreenBounds()
        robot.mouseMove(screen.x + x * screen.width / 1000, screen.y + y * screen.height / 1000)
    }

    override fun click(button: MouseButton) {
        val mask = if (button == MouseButton.RIGHT) InputEvent.BUTTON3_DOWN_MASK else InputEvent.BUTTON1_DOWN_MASK
        robot.mousePress(mask)
        robot.mouseRelease(mask)
    }

    override fun doubleClick() {
        repeat(2) {
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            robot.delay(40)
        }
    }

    override fun setLeftButtonDown(down: Boolean) {
        if (down) robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        else robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    }

    override fun scroll(amount: Int) {
        robot.mouseWheel(amount)
    }

    override fun isValidKeyCombo(combo: String): Boolean = KeyMapping.isValidKeyCombo(combo)

    override fun pressKeyCombo(combo: String) {
        val codes = combo.split("+").map { KeyMapping.keyCodeFor(it) }
        if (codes.any { it == null }) throw IllegalArgumentException("unknown key \"$combo\"")
        pressCombo(codes.filterNotNull())
    }

    // Types any text (Japanese, emoji, symbols...) by pasting it.
    // Robot can't type most non-English characters directly, but pasting always works.
    override suspend fun typeText(text: String) = typingLock.withLock {
        withContext(Dispatchers.IO) { pasteText(text) }
    }

    // Presses Backspace `backspaces` times, then types `text`
    override suspend fun liveType(backspaces: Int, text: String) = typingLock.withLock {
        withContext(Dispatchers.IO) {
            repeat(backspaces) {
                robot.keyPress(KeyEvent.VK_BACK_SPACE)
                robot.keyRelease(KeyEvent.VK_BACK_SPACE)
                robot.delay(4)
            }
            val caps = capsLockOn()
            val pending = StringBuilder()   // characters that have to be pasted
            for (c in text) {
                if (KeyMapping.liveKeyCode(c) != null) {
                    if (pending.isNotEmpty()) {
                        pasteText(pending.toString())
                        pending.clear()
                    }
                    pressLiveKey(c, caps)
                } else {
                    pending.append(c)
                }
            }
            if (pending.isNotEmpty()) pasteText(pending.toString())
        }
    }

    override fun takeScreenshot(): String {
        val size = Toolkit.getDefaultToolkit().screenSize
        val image = robot.createScreenCapture(Rectangle(size))
        screenshotsDir.mkdirs()
        val file = File(screenshotsDir, "remote_screenshot_${System.currentTimeMillis()}.png")
        ImageIO.write(image, "png", file)
        return "Screenshot saved: ${file.name} (in Pictures)"
    }

    override fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    // Holds all the keys down together, then lets go in reverse order (that's how Ctrl+C works)
    private fun pressCombo(codes: List<Int>) {
        codes.forEach { robot.keyPress(it); robot.delay(15) }
        codes.reversed().forEach { robot.keyRelease(it); robot.delay(15) }
    }

    // Puts text on the clipboard and presses Ctrl+V (blocking; callers hold typingLock).
    // The previous clipboard text is put back afterwards (images/files on the clipboard are not restored).
    private fun pasteText(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val previous: String? = try {
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor))
                clipboard.getData(DataFlavor.stringFlavor) as? String else null
        } catch (e: Exception) { null }

        clipboard.setContents(StringSelection(text), null)
        robot.delay(80)
        pressCombo(listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_V))
        robot.delay(250)   // give the other program time to read the clipboard

        if (previous != null) {
            try { clipboard.setContents(StringSelection(previous), null) } catch (e: Exception) { }
        }
    }

    private fun capsLockOn(): Boolean = try {
        Toolkit.getDefaultToolkit().getLockingKeyState(KeyEvent.VK_CAPS_LOCK)
    } catch (e: Exception) { false }

    private fun pressLiveKey(c: Char, caps: Boolean) {
        val code = KeyMapping.liveKeyCode(c) ?: return
        // a capital letter needs Shift, unless Caps Lock is already on (then it is the other way round)
        val shift = c.isLetter() && (c.isUpperCase() != caps)
        if (shift) robot.keyPress(KeyEvent.VK_SHIFT)
        robot.keyPress(code)
        robot.keyRelease(code)
        if (shift) robot.keyRelease(KeyEvent.VK_SHIFT)
        robot.delay(4)
    }
}
