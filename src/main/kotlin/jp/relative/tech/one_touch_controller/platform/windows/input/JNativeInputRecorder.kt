package jp.relative.tech.one_touch_controller.platform.windows.input

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent
import com.github.kwhat.jnativehook.mouse.NativeMouseInputListener
import com.github.kwhat.jnativehook.mouse.NativeMouseWheelEvent
import com.github.kwhat.jnativehook.mouse.NativeMouseWheelListener
import jp.relative.tech.one_touch_controller.domain.ports.RecordingPort
import jp.relative.tech.one_touch_controller.domain.recording.RecEvent
import jp.relative.tech.one_touch_controller.domain.recording.RecordingBuilder
import jp.relative.tech.one_touch_controller.domain.recording.ReplayTarget
import jp.relative.tech.one_touch_controller.domain.recording.STOP_KEY
import jp.relative.tech.one_touch_controller.domain.recording.parseRecording
import jp.relative.tech.one_touch_controller.domain.recording.recordingToText
import jp.relative.tech.one_touch_controller.domain.recording.replayRecording
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.event.InputEvent
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.roundToInt

/**
 * Recorded routines, part 2: listening to the real mouse and keyboard (JNativeHook),
 * persisting `.rec` files and replaying them via AWT [Robot].
 *
 * The listener is started the first time you record or play, and then stays on until the program closes
 * (switching it off and on again is unreliable in this library). Outside a recording or a playback every
 * event is ignored at once: nothing is stored or sent anywhere.
 */
class JNativeInputRecorder(
    private val folder: File,
    private val robotProvider: () -> Robot
) : RecordingPort, NativeKeyListener, NativeMouseInputListener, NativeMouseWheelListener {
    @Volatile private var builder: RecordingBuilder? = null
    @Volatile private var onStopped: ((List<RecEvent>) -> Unit)? = null
    @Volatile private var onProgress: ((Int) -> Unit)? = null
    @Volatile private var stopPlayback = false
    @Volatile private var playing = false
    @Volatile private var lastProgress = 0L

    override val isRecording: Boolean get() = builder != null
    override val isPlaying: Boolean get() = playing

    // ---------- starting the listener (once) ----------
    @Synchronized
    private fun ensureListening() {
        if (GlobalScreen.isNativeHookRegistered()) return
        Logger.getLogger(GlobalScreen::class.java.getPackage().name).apply {      // the library is very talkative
            level = Level.OFF
            useParentHandlers = false
        }
        GlobalScreen.registerNativeHook()
        GlobalScreen.removeNativeKeyListener(this)                                // never add the listeners twice
        GlobalScreen.removeNativeMouseListener(this)
        GlobalScreen.removeNativeMouseMotionListener(this)
        GlobalScreen.removeNativeMouseWheelListener(this)
        GlobalScreen.addNativeKeyListener(this)
        GlobalScreen.addNativeMouseListener(this)
        GlobalScreen.addNativeMouseMotionListener(this)
        GlobalScreen.addNativeMouseWheelListener(this)
    }

    // ---------- recording ----------
    override fun startRecording(progress: (Int) -> Unit, stopped: (List<RecEvent>) -> Unit) {
        if (isRecording) return
        ensureListening()
        onProgress = progress
        onStopped = stopped
        progress(0)
        builder = RecordingBuilder(System.currentTimeMillis())
    }

    override fun stopRecording(fromButton: Boolean) {
        val finished = synchronized(this) {
            val b = builder ?: return
            builder = null
            b
        }
        val callback = onStopped
        onStopped = null
        onProgress = null
        Thread { callback?.invoke(finished.finish(dropLastClick = fromButton)) }.start()
    }

    private fun record(kind: String, a: Int, b: Int = 0, c: Int = 0) {
        val current = builder ?: return
        val now = System.currentTimeMillis()
        if (current.isFull(now)) {
            stopRecording()
            return
        }
        current.add(kind, a, b, c, now)
        if (now - lastProgress > 150) {                  // the window shows the count about 6 times per second
            lastProgress = now
            onProgress?.invoke(current.size())
        }
    }

    // The listener reports real screen pixels. The Robot counts in "scaled" pixels when Windows is set to
    // 125 % or 150 % display size, so the positions are converted here.
    private fun scale(): Double = try {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
            .takeIf { it > 0.0 } ?: 1.0
    } catch (e: Exception) {
        1.0
    }

    private fun sx(e: NativeMouseEvent): Int = (e.x / scale()).roundToInt()
    private fun sy(e: NativeMouseEvent): Int = (e.y / scale()).roundToInt()

    override fun nativeKeyPressed(e: NativeKeyEvent) {
        if (e.rawCode == STOP_KEY) {                 // F9 is never recorded: it means "stop"
            if (isRecording) stopRecording(fromButton = false)
            if (playing) stopPlayback = true
            return
        }
        record("kd", e.rawCode)
    }

    override fun nativeKeyReleased(e: NativeKeyEvent) {
        if (e.rawCode != STOP_KEY) record("ku", e.rawCode)
    }

    override fun nativeMousePressed(e: NativeMouseEvent) { if (e.button in 1..3) record("d", e.button, sx(e), sy(e)) }
    override fun nativeMouseReleased(e: NativeMouseEvent) { if (e.button in 1..3) record("u", e.button, sx(e), sy(e)) }
    override fun nativeMouseMoved(e: NativeMouseEvent) = record("m", sx(e), sy(e))
    override fun nativeMouseDragged(e: NativeMouseEvent) = record("m", sx(e), sy(e))
    override fun nativeMouseWheelMoved(e: NativeMouseWheelEvent) = record("w", e.wheelRotation)

    // ---------- saving and loading ----------
    override fun save(id: String, events: List<RecEvent>) {
        folder.mkdirs()
        val file = File(folder, "$id.rec")
        file.writeText(recordingToText(events))
    }

    override fun load(fileName: String): List<RecEvent> {
        val file = File(folder, fileName)
        if (!file.isFile) throw IllegalArgumentException("the recording ${file.absolutePath} does not exist")
        return parseRecording(file.readText())
    }

    override fun delete(fileName: String) {
        File(folder, fileName).delete()
    }

    override fun listRecordingIds(): Set<String> =
        folder.list()?.map { it.removeSuffix(".rec") }?.toSet() ?: emptySet()

    // ---------- playing ----------
    private fun buttonMask(button: Int): Int = when (button) {
        2 -> InputEvent.BUTTON3_DOWN_MASK      // right
        3 -> InputEvent.BUTTON2_DOWN_MASK      // middle
        else -> InputEvent.BUTTON1_DOWN_MASK   // left
    }

    override fun play(events: List<RecEvent>): Boolean {
        synchronized(this) {
            if (playing) throw IllegalStateException("another recording is already playing")
            if (isRecording) throw IllegalStateException("a recording is being made right now")
            playing = true
            stopPlayback = false
        }
        try {
            ensureListening()                            // so that F9 can stop the playback
            val robot = robotProvider()
            val target = object : ReplayTarget {
                override fun mouseMove(x: Int, y: Int) = robot.mouseMove(x, y)
                override fun mousePress(button: Int) = robot.mousePress(buttonMask(button))
                override fun mouseRelease(button: Int) = robot.mouseRelease(buttonMask(button))
                override fun wheel(notches: Int) = robot.mouseWheel(notches)
                override fun keyPress(keyCode: Int) = robot.keyPress(keyCode)
                override fun keyRelease(keyCode: Int) = robot.keyRelease(keyCode)
            }
            return replayRecording(
                events = events,
                target = target,
                mapKey = KeyMapping::windowsKeyToJava,
                shouldStop = { stopPlayback }
            )
        } finally {
            playing = false
        }
    }
}
