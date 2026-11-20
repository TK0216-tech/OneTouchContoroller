package jp.relative.tech.one_touch_controller.domain.recording

// Recorded routines, part 1: what a recording IS and how it is played back.
//
// A recording is a list of things you did with the mouse and the keyboard, each with the time that passed
// since the one before. It is saved as a small text file in  ~/.onetouch/recordings/  (one line per event):
//
//     120 m 640 360      wait 120 ms, then move the mouse to x=640 y=360
//     35 d 1 640 360     press mouse button 1 (1 = left, 2 = right, 3 = middle) at that place
//     80 u 1 640 360     release it
//     400 w 3            turn the wheel 3 notches (positive = down)
//     250 kd 65          press the key with code 65 (the letter A)
//     90 ku 65           release it
//
// This file has no OS or AWT code in it, so it can be tested anywhere without touching the real mouse.

/** One recorded event. `wait` = milliseconds since the event before it. */
data class RecEvent(val wait: Int, val kind: String, val a: Int = 0, val b: Int = 0, val c: Int = 0)

const val RECORDING_HEADER = "# One Touch Controller recording v1"
const val MAX_RECORDED_EVENTS = 60_000          // about 10 minutes of busy mouse movement
const val MAX_RECORDING_MS = 10 * 60 * 1000L    // a recording stops by itself after 10 minutes
const val MAX_WAIT_MS = 60_000                  // one pause is never longer than a minute
const val STOP_KEY = 120                        // F9 (Windows key code): stops a recording and stops a playback

private val recordingKinds = setOf("m", "d", "u", "w", "kd", "ku")

fun recordingToText(events: List<RecEvent>): String = buildString {
    append(RECORDING_HEADER).append('\n')
    for (e in events) {
        append(e.wait).append(' ').append(e.kind).append(' ').append(e.a)
        when (e.kind) {
            "m" -> append(' ').append(e.b)
            "d", "u" -> append(' ').append(e.b).append(' ').append(e.c)
        }
        append('\n')
    }
}

/** Reads a recording file. Lines that are not understood are skipped. */
fun parseRecording(text: String): List<RecEvent> {
    val events = ArrayList<RecEvent>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        if (events.size >= MAX_RECORDED_EVENTS) break
        val p = line.split(' ')
        val wait = p.getOrNull(0)?.toIntOrNull() ?: continue
        val kind = p.getOrNull(1) ?: continue
        if (kind !in recordingKinds) continue
        val a = p.getOrNull(2)?.toIntOrNull() ?: continue
        val b = p.getOrNull(3)?.toIntOrNull() ?: 0
        val c = p.getOrNull(4)?.toIntOrNull() ?: 0
        if ((kind == "d" || kind == "u") && a !in 1..3) continue
        events.add(RecEvent(wait.coerceIn(0, MAX_WAIT_MS), kind, a, b, c))
    }
    return events
}

/** Total playing time in whole seconds (for the window: "12 s"). */
fun recordingSeconds(events: List<RecEvent>): Int = ((events.sumOf { it.wait.toLong() } + 999) / 1000).toInt()

/**
 * Collects the events while a recording is running.
 * Plain mouse movement arrives hundreds of times per second, so only one position every 25 ms is kept.
 */
class RecordingBuilder(private val startMs: Long) {
    private val events = ArrayList<RecEvent>()
    private var lastMs = startMs
    private var lastMoveMs = 0L

    @Synchronized
    fun add(kind: String, a: Int, b: Int, c: Int, nowMs: Long) {
        if (isFull(nowMs)) return
        if (kind == "m") {
            if (nowMs - lastMoveMs < 25) return
            lastMoveMs = nowMs
        }
        val wait = (nowMs - lastMs).coerceIn(0L, MAX_WAIT_MS.toLong()).toInt()
        // the very first pause (the time you needed to get ready) is cut down to a short one
        events.add(RecEvent(if (events.isEmpty()) minOf(wait, 300) else wait, kind, a, b, c))
        lastMs = nowMs
    }

    @Synchronized
    fun isFull(nowMs: Long): Boolean = events.size >= MAX_RECORDED_EVENTS || nowMs - startMs > MAX_RECORDING_MS

    @Synchronized
    fun size(): Int = events.size

    /**
     * The finished list. Keys and buttons that were still held down when recording stopped are released at the end.
     * [dropLastClick] = recording was stopped with the Stop button: that last click (and the mouse movement after it)
     * is not part of the task, so it is removed.
     */
    @Synchronized
    fun finish(dropLastClick: Boolean = false): List<RecEvent> {
        val result = ArrayList(events)
        if (dropLastClick) {
            val lastPress = result.indexOfLast { it.kind == "d" }
            if (lastPress >= 0) while (result.size > lastPress) result.removeAt(result.size - 1)
        }
        val keysDown = LinkedHashSet<Int>()
        val buttonsDown = LinkedHashSet<Int>()
        for (e in result) {
            when (e.kind) {
                "kd" -> keysDown.add(e.a)
                "ku" -> keysDown.remove(e.a)
                "d" -> buttonsDown.add(e.a)
                "u" -> buttonsDown.remove(e.a)
            }
        }
        // a key that was already down when recording started only has a "release": remove it, it would do nothing useful
        val started = HashSet<Int>()
        val cleaned = result.filter { e ->
            when (e.kind) {
                "kd" -> { started.add(e.a); true }
                "ku" -> e.a in started
                else -> true
            }
        }.toMutableList()
        val last = cleaned.lastOrNull()
        for (button in buttonsDown) cleaned.add(RecEvent(20, "u", button, last?.b ?: 0, last?.c ?: 0))
        for (key in keysDown) cleaned.add(RecEvent(20, "ku", key))
        return cleaned
    }
}

/** What a playback needs to be able to do. Platform implementation uses Robot. */
interface ReplayTarget {
    fun mouseMove(x: Int, y: Int)
    fun mousePress(button: Int)       // 1 = left, 2 = right, 3 = middle
    fun mouseRelease(button: Int)
    fun wheel(notches: Int)
    fun keyPress(keyCode: Int)
    fun keyRelease(keyCode: Int)
}

/**
 * Plays a recording. Returns true if it played to the end, false if it was stopped (F9).
 * Whatever happens, every key and button that is still held down is released at the end,
 * so the PC is never left with "Ctrl stuck down".
 */
fun replayRecording(
    events: List<RecEvent>,
    target: ReplayTarget,
    mapKey: (Int) -> Int?,
    shouldStop: () -> Boolean,
    sleep: (Long) -> Unit = { Thread.sleep(it) }
): Boolean {
    val keysDown = LinkedHashSet<Int>()
    val buttonsDown = LinkedHashSet<Int>()
    try {
        for (e in events) {
            var left = e.wait.toLong()
            while (left > 0) {                      // wait in small pieces so F9 stops it quickly
                if (shouldStop()) return false
                val piece = minOf(left, 20L)
                sleep(piece)
                left -= piece
            }
            if (shouldStop()) return false
            try {
                when (e.kind) {
                    "m" -> target.mouseMove(e.a, e.b)
                    "d" -> { target.mouseMove(e.b, e.c); target.mousePress(e.a); buttonsDown.add(e.a) }
                    "u" -> { target.mouseMove(e.b, e.c); target.mouseRelease(e.a); buttonsDown.remove(e.a) }
                    "w" -> target.wheel(e.a)
                    "kd" -> mapKey(e.a)?.let { target.keyPress(it); keysDown.add(it) }
                    "ku" -> mapKey(e.a)?.let { if (keysDown.remove(it)) target.keyRelease(it) }
                }
            } catch (ex: IllegalArgumentException) {
                // a key this keyboard layout does not have: skip it and go on
            }
        }
        return true
    } finally {
        for (button in buttonsDown) try { target.mouseRelease(button) } catch (ex: Exception) { }
        for (key in keysDown) try { target.keyRelease(key) } catch (ex: Exception) { }
    }
}

/** Turns the name you typed ("Open my mail!") into a file-safe id ("open-my-mail"). */
fun recordingId(name: String, taken: Set<String>): String {
    val base = name.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
        .replace(Regex("-+"), "-").trim('-').take(24).trim('-').ifEmpty { "recording" }
    if (base !in taken) return base
    var n = 2
    while ("$base-$n" in taken) n++
    return "$base-$n"
}
