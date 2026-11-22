import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.InputEvent
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlin.concurrent.thread

// ================= SETTINGS =================
const val PORT = 8080                  // HTTP port for commands (the app uses 8080)
const val DISCOVERY_PORT = 8888        // UDP port for auto-discovery

@Volatile var serverToken = "change-me-123"               // can be changed in the window
@Volatile var appLog: (String) -> Unit = { println(it) }  // the window replaces this with its log list

// The token is saved in your user folder so you don't retype it every time
val tokenFile = File(System.getProperty("user.home"), ".onetouch_token")

fun loadToken() {
    try {
        if (tokenFile.exists()) {
            val saved = tokenFile.readText().trim()
            if (saved.isNotEmpty()) serverToken = saved
        }
    } catch (e: Exception) {
        // ignore: we simply keep the default token
    }
}

fun saveToken() {
    try {
        tokenFile.writeText(serverToken)
    } catch (e: Exception) {
        appLog("Could not save token: ${e.message}")
    }
}

// ================= HELPERS =================
fun startProcess(vararg cmd: String) {
    ProcessBuilder(*cmd).start()
}

fun powershell(script: String) {
    startProcess("powershell", "-NoProfile", "-Command", script)
}

// Sends a media/volume key through Windows (175=vol up, 174=vol down, 173=mute,
// 179=play/pause, 176=next track, 177=previous track)
fun pressKey(code: Int) {
    powershell("(New-Object -ComObject WScript.Shell).SendKeys([char]$code)")
}

// Reads a value from a simple JSON body, e.g. jsonField("{\"dx\":5}", "dx") -> "5"
fun jsonField(body: String, key: String): String? =
    Regex("\"$key\"\\s*:\\s*\"?([^\",}\\s]+)\"?").find(body)?.groupValues?.get(1)

// Robot = Java's tool for controlling the mouse/keyboard and capturing the screen
val robot: Robot by lazy { Robot() }

fun takeScreenshot(): String {
    val size = Toolkit.getDefaultToolkit().screenSize
    val image = robot.createScreenCapture(Rectangle(size))
    val folder = File(System.getProperty("user.home"), "Pictures")
    folder.mkdirs()
    val file = File(folder, "remote_screenshot_${System.currentTimeMillis()}.png")
    ImageIO.write(image, "png", file)
    return "Screenshot saved: ${file.name} (in Pictures)"
}

// ================= KEYBOARD =================
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

// Holds all the keys down together, then lets go in reverse order (that's how Ctrl+C works)
fun pressCombo(codes: List<Int>) {
    codes.forEach { robot.keyPress(it); robot.delay(15) }
    codes.reversed().forEach { robot.keyRelease(it); robot.delay(15) }
}

// Only one typing job at a time, because pasting borrows the clipboard and keystrokes must stay in order
val typingLock = Mutex()

// Puts text on the clipboard and presses Ctrl+V (blocking; callers hold typingLock).
// The previous clipboard text is put back afterwards (images/files on the clipboard are not restored).
fun pasteText(text: String) {
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

// Types any text (Japanese, emoji, symbols...) by pasting it.
// Robot can't type most non-English characters directly, but pasting always works.
suspend fun typeText(text: String) = typingLock.withLock {
    withContext(Dispatchers.IO) { pasteText(text) }
}

// ---- Live typing (English): real key presses, so it feels like a normal keyboard ----
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

fun capsLockOn(): Boolean = try {
    Toolkit.getDefaultToolkit().getLockingKeyState(KeyEvent.VK_CAPS_LOCK)
} catch (e: Exception) { false }

fun pressLiveKey(c: Char, caps: Boolean) {
    val code = liveKeyCode(c) ?: return
    // a capital letter needs Shift, unless Caps Lock is already on (then it is the other way round)
    val shift = c.isLetter() && (c.isUpperCase() != caps)
    if (shift) robot.keyPress(KeyEvent.VK_SHIFT)
    robot.keyPress(code)
    robot.keyRelease(code)
    if (shift) robot.keyRelease(KeyEvent.VK_SHIFT)
    robot.delay(4)
}

// Presses Backspace `backspaces` times, then types `text`
suspend fun liveType(backspaces: Int, text: String) = typingLock.withLock {
    withContext(Dispatchers.IO) {
        repeat(backspaces) {
            robot.keyPress(KeyEvent.VK_BACK_SPACE)
            robot.keyRelease(KeyEvent.VK_BACK_SPACE)
            robot.delay(4)
        }
        val caps = capsLockOn()
        val pending = StringBuilder()   // characters that have to be pasted
        for (c in text) {
            if (liveKeyCode(c) != null) {
                if (pending.isNotEmpty()) { pasteText(pending.toString()); pending.clear() }
                pressLiveKey(c, caps)
            } else {
                pending.append(c)
            }
        }
        if (pending.isNotEmpty()) pasteText(pending.toString())
    }
}

// Returns true if the phone sent the right token; otherwise answers 401
suspend fun ApplicationCall.checkToken(): Boolean {
    val id = Guard.clientId(this)               // phone address (for internet visitors: the address Cloudflare reports)

    val wait = Guard.blockedSeconds(id)
    if (wait > 0) {                              // too many wrong tokens recently
        respondText(
            "Too many wrong tokens. Try again in $wait seconds.",
            status = HttpStatusCode.TooManyRequests
        )
        return false
    }

    if (Guard.tokensEqual(request.headers["X-Auth-Token"], serverToken)) {
        if (Guard.recordSuccess(id)) {
            appLog("Device connected: $id" + if (Guard.isViaTunnel(this)) " (via the internet)" else "")
        }
        if (Devices.seen(id, request.headers["User-Agent"], Guard.isViaTunnel(this))) {
            Presenter.show(tr("%1 connected", tr(appFromUserAgent(request.headers["User-Agent"]))))
        }
        return true
    }

    val nowBlocked = Guard.recordFailure(id)
    appLog("Rejected a request from $id: wrong token" + if (nowBlocked) " - blocked for 5 minutes" else "")
    respond(HttpStatusCode.Unauthorized)
    return false
}

// Finds this PC's local network addresses (the ones you type into the phone app)
fun localAddresses(): List<String> {
    val result = mutableListOf<String>()
    val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return result
    for (ni in interfaces) {
        if (!ni.isUp || ni.isLoopback) continue
        for (addr in ni.inetAddresses.toList()) {
            if (addr is Inet4Address && addr.isSiteLocalAddress) {
                result.add("${addr.hostAddress}   (${ni.displayName})")
            }
        }
    }
    return result
}

// ================= WHITELIST OF COMMANDS =================
// key = what the phone sends, value = what the PC does (returns a message for the app)
val allowedCommands: Map<String, () -> String> = mapOf(
    // Programs
    "open_notepad" to { startProcess("notepad.exe"); "Opened Notepad" },
    "open_calculator" to { startProcess("calc.exe"); "Opened Calculator" },
    "open_explorer" to { startProcess("explorer.exe"); "Opened File Explorer" },
    "open_chrome" to { startProcess("cmd", "/c", "start", "", "chrome"); "Opened Chrome" },

    // Websites
    "open_youtube" to { startProcess("cmd", "/c", "start", "", "https://www.youtube.com"); "Opened YouTube" },
    "open_google" to { startProcess("cmd", "/c", "start", "", "https://www.google.com"); "Opened Google" },

    // Your own automation script (change the path!)
    "run_script_1" to { startProcess("cmd", "/c", "start", "", "C:\\scripts\\script1.bat"); "Ran script 1" },

    // Media and volume
    "volume_up" to { pressKey(175); "Volume up" },
    "volume_down" to { pressKey(174); "Volume down" },
    "mute" to { pressKey(173); "Mute toggled" },
    "play_pause" to { pressKey(179); "Play/Pause" },
    "next_track" to { pressKey(176); "Next track" },
    "prev_track" to { pressKey(177); "Previous track" },

    // System
    "lock_screen" to { startProcess("rundll32.exe", "user32.dll,LockWorkStation"); "PC locked" },
    "screenshot" to { takeScreenshot() },
    // (Guard mode refuses shutdowns, so it is turned off first when YOU ask for one from the phone)
    "shutdown_60" to { GuardMode.turnOff("shutdown from the phone"); startProcess("shutdown", "/s", "/t", "60"); "Shutting down in 60 seconds" },
    "restart_60" to { GuardMode.turnOff("restart from the phone"); startProcess("shutdown", "/r", "/t", "60"); "Restarting in 60 seconds" },
    "cancel_shutdown" to { startProcess("shutdown", "/a"); "Shutdown/restart cancelled" },
)

// ================= ROUTINES (automation) =================
// The routines themselves live in ~/.onetouch/routines.txt (see Routines.kt). This is what their steps really do on the PC.
val routineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// Ids of the routines that are running right now. The PC window watches this ("Running..." on the button).
var runningRoutines by mutableStateOf(setOf<String>())

// How the last run of each routine ended: id -> number of steps that failed (0 = everything worked).
// The phone asks for this (GET /routine/status) to show "Finished".
val routineResults = java.util.concurrent.ConcurrentHashMap<String, Int>()

/**
 * Starts a routine in the background. Used by the phone (/routine/run) and by the Run buttons in the PC window.
 * Returns the message for the phone: "Started: ..." or "Already running: ...".
 */
fun startRoutine(routine: Routine, from: String): String {
    if (!RoutineStore.running.add(routine.id)) return "Already running: ${routine.name}"
    runningRoutines = runningRoutines + routine.id
    appLog("Routine started ($from): ${routine.name}")
    val fromPhone = from.startsWith("phone")
    if (fromPhone) Presenter.show(tr("Routine: %1", routine.name))
    routineResults.remove(routine.id)
    routineScope.launch {
        var failed = 0
        // the real actions, but failed steps are counted (executeRoutine reports them through log)
        val counting = object : RoutineActions by RealRoutineActions {
            override fun log(message: String) {
                if (message.trimStart().startsWith("This step failed")) failed++
                RealRoutineActions.log(message)
            }
        }
        try {
            executeRoutine(routine, counting)
        } finally {
            routineResults[routine.id] = failed           // first the result, then "not running" (no gap for the phone)
            RoutineStore.running.remove(routine.id)
            runningRoutines = runningRoutines - routine.id
            if (fromPhone) Presenter.show(tr("Routine finished: %1", routine.name))
        }
    }
    return "Started: ${routine.name}"
}

fun isValidKeyCombo(combo: String): Boolean = combo.split("+").all { keyCodeFor(it) != null }

fun reloadRoutines() {
    RoutineStore.load(allowedCommands.keys, ::isValidKeyCombo)
    appLog("Routines loaded: ${RoutineStore.routines.size}" +
            if (RoutineStore.problems.isEmpty()) "" else " (${RoutineStore.problems.size} problem(s) in the file)")
}

/**
 * Deletes a routine from routines.txt (and its recording, if no other routine uses it).
 * Returns an error message, or null when it worked.
 */
fun deleteRoutine(routine: Routine): String? {
    if (routine.id in RoutineStore.running) return "\"${routine.name}\" is running. Wait until it has finished."
    return try {
        val text = RoutineStore.file.readText()
        val newText = removeRoutineText(text, routine.id) ?: return "\"${routine.name}\" is not in the routines file any more. Press Reload."
        RoutineStore.file.writeText(newText)
        reloadRoutines()
        // a recording that no other routine uses is deleted too
        val stillUsed = RoutineStore.routines.flatMap { r -> r.steps.filterIsInstance<Step.Replay>().map { it.file } }.toSet()
        routine.steps.filterIsInstance<Step.Replay>().map { it.file }.filter { it !in stillUsed }.forEach { name ->
            File(InputRecorder.folder, name).delete()
        }
        appLog("Routine deleted: ${routine.name}")
        null
    } catch (e: Exception) {
        "Could not change the routines file: ${e.message}"
    }
}

object RealRoutineActions : RoutineActions {
    override fun launch(program: String, args: List<String>) {
        val builder = ProcessBuilder(listOf(program) + args)
        File(program).parentFile?.takeIf { it.isDirectory }?.let { builder.directory(it) }   // many programs expect their own folder
        builder.start()
    }

    // rundll32 + FileProtocolHandler opens websites, files and folders the same way a double-click does
    override fun open(target: String) {
        ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", target).start()
    }

    override fun key(combo: String) {
        val codes = combo.split("+").map { keyCodeFor(it) }
        if (codes.any { it == null }) throw IllegalArgumentException("unknown key \"$combo\"")
        pressCombo(codes.filterNotNull())
    }

    override fun close(exe: String) {
        ProcessBuilder("taskkill", "/IM", exe, "/F").redirectErrorStream(true).start().waitFor()
    }

    override fun power(plan: String) {
        val guid = when (plan) {
            "high" -> "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c"
            "saver" -> "a1841308-3541-4fab-bc81-f71556f20b4f"
            else -> "381b4222-f694-41f0-9685-ff5bb260df2e"      // balanced
        }
        val process = ProcessBuilder("powercfg", "/setactive", guid).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() != 0) throw RuntimeException("this PC has no \"$plan\" power plan (${output.take(80)})")
    }

    override fun command(id: String) {
        val action = allowedCommands[id] ?: throw IllegalArgumentException("unknown command \"$id\"")
        action()
    }

    // Repeats mouse and keyboard actions that were recorded in the PC window (F9 stops it)
    override fun replay(file: String) {
        val events = InputRecorder.load(file)
        if (events.isEmpty()) throw RuntimeException("the recording $file is empty")
        appLog("  Playing ${events.size} recorded actions (about ${recordingSeconds(events)} s). Press F9 on the PC to stop.")
        if (!InputRecorder.play(events, robot)) appLog("  Playback stopped with F9")
    }

    override fun valorant() {
        val riot = findRiotClient()
            ?: throw RuntimeException("Riot Client not found. Use:  launch \"<path to RiotClientServices.exe>\" --launch-product=valorant --launch-patchline=live")
        launch(riot, VALORANT_ARGS)
    }

    override fun log(message: String) = appLog(message)
}

// ================= SERVER ROUTES =================
// How many live connections (WebSockets) are open at the moment, including ones that have not logged in yet
val liveConnections = AtomicInteger(0)

// ================= SCREEN VIEWING =================
// How many phones are watching the screen right now (the window shows a warning while it is more than 0)
object ScreenState {
    private val counter = AtomicInteger(0)
    var viewers by mutableStateOf(0)
        private set

    fun started() { viewers = counter.incrementAndGet() }
    fun stopped() { viewers = counter.decrementAndGet().coerceAtLeast(0) }
}

// Sends the screen to one phone as a series of small JPEG pictures, as many as the phone can take.
// The next picture is only sent after the phone has confirmed the earlier ones (the "a" message in
// SocketProtocol.kt). On a slow connection pictures are therefore skipped instead of piling up and arriving late.
suspend fun streamScreen(session: WebSocketSession, settings: SocketCommand.View, inFlight: AtomicInteger) {
    val screen = primaryScreenBounds()
    val view = viewRect(settings.zoomPercent, settings.cx, settings.cy)
    var lastFingerprint = 0
    var lastSent = 0L
    while (currentCoroutineContext().isActive) {
        if (!Guard.allowScreenView) {                       // switched off in the window while watching
            session.send(Frame.Text("viewdenied"))
            return
        }
        val now = System.currentTimeMillis()
        if (inFlight.get() >= 2 || now - lastSent < 60) {  // at most about 15 pictures a second
            delay(5)
            continue
        }
        val picture = try {
            val shot = robot.createScreenCapture(screen)
            val p = MouseInfo.getPointerInfo()?.location
            val cursor = p?.let { Pair((it.x - screen.x).toDouble() / screen.width, (it.y - screen.y).toDouble() / screen.height) }
            renderScreenPicture(shot, view, settings.width, cursor)
        } catch (e: Exception) {
            session.send(Frame.Text("screenerror ${e.message ?: "cannot capture the screen"}"))
            delay(2000)
            continue
        }
        val fingerprint = pictureFingerprint(picture)
        if (fingerprint == lastFingerprint && now - lastSent < 2000) {   // nothing changed: send nothing, look again soon
            delay(120)
            continue
        }
        lastFingerprint = fingerprint
        lastSent = System.currentTimeMillis()
        inFlight.incrementAndGet()
        session.send(Frame.Binary(true, encodeJpeg(picture, settings.quality)))
    }
}

fun Application.remoteModule() {
    install(WebSockets) {
        pingPeriodMillis = 15_000     // keeps the connection alive through Cloudflare / routers
        timeoutMillis = 30_000
        maxFrameSize = 4096           // our messages are tiny; anything bigger is refused
    }
    routing {
        // The plain address (http://<PC IP>:8080 or the https://....trycloudflare.com link) opens the web version
        // in a browser. Programs that do not ask for a web page (like the page's own status check) still get
        // the short text answer.
        get("/") {
            // only a real browser (its User-Agent starts with "Mozilla") gets the page. Apps such as Gesture Operate
            // also say "text/html" in their Accept header, but they only want the short text.
            val wantsPage = call.request.headers["Accept"]?.contains("text/html") == true &&
                    call.request.headers["User-Agent"]?.contains("Mozilla") == true
            if (wantsPage) {
                call.response.headers.append("Cache-Control", "no-cache")
                call.respondText(WEB_APP_HTML, ContentType.Text.Html)
            } else {
                call.respondText("PC Remote server is running")
            }
        }

        // ---- Web version of the remote (iPhone Safari, any browser): http://<PC IP>:8080/app ----
        // The page itself is public; every action still needs the access token.
        get("/app") {
            call.response.headers.append("Cache-Control", "no-cache")
            call.respondText(WEB_APP_HTML, ContentType.Text.Html)
        }

        // ---- Run a whitelisted command ----
        post("/run") {
            if (!call.checkToken()) return@post
            val name = jsonField(call.receiveText(), "command")
            val action = allowedCommands[name]
            if (action == null) {
                appLog("Rejected unknown command: $name")
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            if (name in Guard.dangerousOverInternet && Guard.isViaTunnel(call) && !Guard.allowDangerousOverInternet) {
                appLog("Blocked over the internet: $name")
                call.respondText(
                    "Blocked: this command is switched off for internet access (see the PC window).",
                    status = HttpStatusCode.Forbidden
                )
                return@post
            }
            appLog("Command: $name")
            val message = try {
                action()
            } catch (e: Exception) {
                appLog("Command failed: ${e.message}")
                call.respondText("Failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                return@post
            }
            Presenter.show(tr(commandNames[name] ?: name.orEmpty()))
            call.respondText(message)
        }

        // ---- PC status for the phones: battery, charging, CPU and memory ----
        get("/status") {
            if (!call.checkToken()) return@get
            call.respondText(withContext(Dispatchers.IO) { readPcStatus() }.toJson(), ContentType.Application.Json)
        }

        // ---- Fast live connection for the touchpad (see SocketProtocol.kt) ----
        // Same token as everything else: the first message must be "auth <token>".
        webSocket("/ws") {
            if (liveConnections.incrementAndGet() > 20) {      // nobody needs more than a few
                liveConnections.decrementAndGet()
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "busy"))
                return@webSocket
            }
            val session = this
            var streamJob: Job? = null
            var viewing = false
            var buttonHeld = false
            val inFlight = AtomicInteger(0)      // pictures sent that the phone has not confirmed yet
            val id = Guard.clientId(call)
            var listed = false
            try {
                val wait = Guard.blockedSeconds(id)
                if (wait > 0) {
                    send(Frame.Text("blocked $wait"))
                    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "blocked"))
                    return@webSocket
                }

                // the login message has to arrive within 5 seconds
                val first = withTimeoutOrNull(5000) { incoming.receiveCatching().getOrNull() }
                val given = (first as? Frame.Text)?.readText()?.takeIf { it.startsWith("auth ") }?.substring(5)
                if (!Guard.tokensEqual(given, serverToken)) {
                    val nowBlocked = Guard.recordFailure(id)
                    appLog("Rejected a live connection from $id: wrong token" + if (nowBlocked) " - blocked for 5 minutes" else "")
                    send(Frame.Text("denied"))
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "denied"))
                    return@webSocket
                }
                if (Guard.recordSuccess(id)) {
                    appLog("Device connected: $id" + if (Guard.isViaTunnel(call)) " (via the internet)" else "")
                }
                listed = true
                if (Devices.liveOpened(id, call.request.headers["User-Agent"], Guard.isViaTunnel(call))) {
                    Presenter.show(tr("%1 connected", tr(appFromUserAgent(call.request.headers["User-Agent"]))))
                }
                send(Frame.Text("ok"))

                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    if (Guard.blockedSeconds(id) > 0) {          // disconnected in the PC window
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "disconnected"))
                        break
                    }
                    when (val command = parseSocketCommand(frame.readText())) {
                        is SocketCommand.Move -> {
                            val p = MouseInfo.getPointerInfo().location
                            robot.mouseMove(p.x + command.dx, p.y + command.dy)
                        }
                        is SocketCommand.Click -> {
                            val button = if (command.right) InputEvent.BUTTON3_DOWN_MASK else InputEvent.BUTTON1_DOWN_MASK
                            robot.mousePress(button)
                            robot.mouseRelease(button)
                            Presenter.show(if (command.right) tr("Right click") else tr("Click"))
                        }
                        is SocketCommand.Scroll -> robot.mouseWheel(command.amount)
                        is SocketCommand.Ping -> send(Frame.Text("q ${command.stamp}"))
                        is SocketCommand.Point -> {
                            val screen = primaryScreenBounds()
                            robot.mouseMove(screen.x + command.x * screen.width / 1000, screen.y + command.y * screen.height / 1000)
                        }
                        is SocketCommand.DoubleClick -> {
                            repeat(2) {
                                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                                robot.delay(40)
                            }
                            Presenter.show(tr("Double click"))
                        }
                        is SocketCommand.Button -> {
                            if (command.down) robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                            else robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                            buttonHeld = command.down
                        }
                        is SocketCommand.Ack -> inFlight.updateAndGet { maxOf(0, it - 1) }
                        // direct typing from the other device's keyboard (same powers as /keyboard/live and /keyboard/key)
                        is SocketCommand.TypeText -> { liveType(0, command.text); Presenter.show(tr("Typing")) }
                        is SocketCommand.KeyPress -> {
                            val codes = command.combo.split("+").map { keyCodeFor(it) }
                            if (codes.none { it == null }) withContext(Dispatchers.IO) { pressCombo(codes.filterNotNull()) }
                            Presenter.show(tr("Key: %1", command.combo))
                        }
                        is SocketCommand.View -> {
                            if (!Guard.allowScreenView) {
                                send(Frame.Text("viewdenied"))
                            } else {
                                streamJob?.cancel()
                                inFlight.set(0)
                                if (!viewing) {
                                    viewing = true
                                    ScreenState.started()
                                    appLog("Screen viewing started: $id" + if (Guard.isViaTunnel(call)) " (via the internet)" else "")
                                    Presenter.show(tr("Viewing the PC screen"))
                                }
                                streamJob = launch(Dispatchers.IO) { streamScreen(session, command, inFlight) }
                            }
                        }
                        is SocketCommand.ViewOff -> {
                            streamJob?.cancel()
                            streamJob = null
                            if (viewing) {
                                viewing = false
                                ScreenState.stopped()
                                appLog("Screen viewing stopped")
                            }
                        }
                        null -> { }   // unknown message: ignored
                    }
                }
            } finally {
                streamJob?.cancel()
                if (viewing) {
                    ScreenState.stopped()
                    appLog("Screen viewing stopped")
                }
                if (buttonHeld) robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)   // never leave the mouse button stuck down
                if (listed) Devices.liveClosed(id)
                liveConnections.decrementAndGet()
            }
        }

        // ---- Guard mode: block the PC's own mouse and keyboard while you use it from the phone (see GuardMode.kt) ----
        get("/guard") {
            if (!call.checkToken()) return@get
            call.respondText(GuardMode.statusText())          // "off", "nopassword" or "on <wrong passwords typed at the PC>"
        }

        post("/guard") {
            if (!call.checkToken()) return@post
            val on = jsonField(call.receiveText(), "on") == "true"
            val from = if (Guard.isViaTunnel(call)) "from the phone, via the internet" else "from the phone"
            try {
                if (on) GuardMode.turnOn(from) else GuardMode.turnOff(from)
                Presenter.show(if (on) tr("Guard mode on") else tr("Guard mode off"))
                call.respondText(if (on) "Guard mode on" else "Guard mode off")
            } catch (e: Exception) {
                appLog("Guard mode could not start: ${e.message}")
                call.respondText(e.message ?: "Guard mode could not start", status = HttpStatusCode.Conflict)
            }
        }

        // ---- A message from the phone, shown on the PC (on the lock screen while Guard mode is on) ----
        post("/message") {
            if (!call.checkToken()) return@post
            val text = call.receiveText().trim()
            if (text.isEmpty()) {
                call.respondText("The message is empty", status = HttpStatusCode.BadRequest)
                return@post
            }
            GuardMode.showMessage(text)
            call.respondText("Message shown on the PC")
        }

        // ---- Routines (automation): the phone only sends the NAME of a routine ----
        get("/routines") {
            if (!call.checkToken()) return@get
            // one line per routine:  id <TAB> name <TAB> "!" if it contains shutdown/restart/script commands
            val list = RoutineStore.routines.joinToString("\n") { r ->
                r.id + "\t" + r.name + "\t" + (if (r.isDangerous(Guard.dangerousOverInternet)) "!" else "")
            }
            call.respondText(list)
        }

        // Is the routine still running? "running", "finished <failed steps>" or "unknown"
        get("/routine/status") {
            if (!call.checkToken()) return@get
            val id = call.request.queryParameters["id"] ?: ""
            val answer = when {
                id in RoutineStore.running -> "running"
                routineResults.containsKey(id) -> "finished ${routineResults[id]}"
                else -> "unknown"
            }
            call.respondText(answer)
        }

        post("/routine/run") {
            if (!call.checkToken()) return@post
            val routine = RoutineStore.find(jsonField(call.receiveText(), "id"))
            if (routine == null) {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            if (routine.isDangerous(Guard.dangerousOverInternet) && Guard.isViaTunnel(call) && !Guard.allowDangerousOverInternet) {
                appLog("Blocked over the internet: routine ${routine.id}")
                call.respondText(
                    "Blocked: this routine has shutdown/restart/script steps, which are disabled over the internet.",
                    status = HttpStatusCode.Forbidden
                )
                return@post
            }
            // runs in the background, the phone does not have to wait
            call.respondText(startRoutine(routine, if (Guard.isViaTunnel(call)) "phone, via the internet" else "phone"))
        }

        // ---- Touchpad: move the mouse by (dx, dy) pixels ----
        post("/mouse/move") {
            if (!call.checkToken()) return@post
            val body = call.receiveText()
            val dx = jsonField(body, "dx")?.toIntOrNull() ?: 0
            val dy = jsonField(body, "dy")?.toIntOrNull() ?: 0
            val p = MouseInfo.getPointerInfo().location
            robot.mouseMove(p.x + dx, p.y + dy)
            call.respondText("OK")
        }

        // ---- Touchpad: click ("left", "right" or "double") ----
        post("/mouse/click") {
            if (!call.checkToken()) return@post
            val which = jsonField(call.receiveText(), "button")
            val button = if (which == "right") InputEvent.BUTTON3_DOWN_MASK else InputEvent.BUTTON1_DOWN_MASK
            repeat(if (which == "double") 2 else 1) {
                robot.mousePress(button)
                robot.mouseRelease(button)
                if (which == "double") robot.delay(40)
            }
            Presenter.show(when (which) { "right" -> tr("Right click"); "double" -> tr("Double click"); else -> tr("Click") })
            call.respondText("OK")
        }

        // ---- Touchpad: scroll (positive = down, negative = up) ----
        post("/mouse/scroll") {
            if (!call.checkToken()) return@post
            val amount = jsonField(call.receiveText(), "amount")?.toIntOrNull() ?: 0
            robot.mouseWheel(amount)
            call.respondText("OK")
        }

        // ---- Keyboard: type a piece of text (plain UTF-8 text in the request body) ----
        post("/keyboard/type") {
            if (!call.checkToken()) return@post
            val text = call.receiveText()
            if (text.isEmpty() || text.length > 5000) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            try {
                typeText(text)
            } catch (e: Exception) {
                appLog("Typing failed: ${e.message}")
                call.respondText("Failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                return@post
            }
            appLog("Typed ${text.length} characters")   // the text itself is not logged (could be a password)
            Presenter.show(tr("Typed %1 characters", text.length))     // the text itself is not shown either
            call.respondText("Typed ${text.length} characters")
        }

        // ---- Keyboard: live typing. Body = "<number of backspaces>\n<text to type>" ----
        post("/keyboard/live") {
            if (!call.checkToken()) return@post
            val body = call.receiveText()
            val nl = body.indexOf('\n')
            val backspaces = if (nl > 0) body.substring(0, nl).trim().toIntOrNull() else null
            val text = if (nl >= 0) body.substring(nl + 1) else ""
            if (backspaces == null || backspaces !in 0..500 || text.length > 500) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            try {
                liveType(backspaces, text)   // not logged: it happens on every key press and could be a password
                Presenter.show(tr("Typing"))
            } catch (e: Exception) {
                call.respondText("Failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                return@post
            }
            call.respondText("OK")
        }

        // ---- Keyboard: press a key or shortcut, e.g. {"key":"enter"} or {"key":"ctrl+c"} ----
        post("/keyboard/key") {
            if (!call.checkToken()) return@post
            val combo = jsonField(call.receiveText(), "key")?.lowercase()
            val codes = combo?.split("+")?.map { keyCodeFor(it) }
            if (combo == null || codes == null || codes.any { it == null }) {
                appLog("Rejected unknown key: $combo")
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            try {
                withContext(Dispatchers.IO) { pressCombo(codes.filterNotNull()) }
            } catch (e: Exception) {
                appLog("Key failed: ${e.message}")
                call.respondText("Failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                return@post
            }
            appLog("Key: $combo")
            Presenter.show(tr("Key: %1", combo))
            call.respondText("OK")
        }
    }
}

// ================= SERVER ON/OFF CONTROL =================
// Starts and stops the HTTP server and the auto-discovery listener together.
class ServerController {
    var running by mutableStateOf(false)   // Compose watches this, so the window and the tray update by themselves
        private set
    private var stopServer: (() -> Unit)? = null
    private var discoverySocket: DatagramSocket? = null

    fun start() {
        if (running) return
        val s = embeddedServer(Netty, port = PORT, host = "0.0.0.0") { remoteModule() }
        s.start(wait = false)              // wait = false -> the program keeps running (the window stays usable)
        stopServer = { s.stop(500, 1000) }
        startDiscovery()
        running = true
        appLog("Server started on port $PORT")
    }

    fun stop() {
        if (!running) return
        tunnel.stop()          // no server = nothing for the internet tunnel to connect to
        stopDiscovery()
        try {
            stopServer?.invoke()
        } catch (e: Exception) {
            appLog("Error while stopping: ${e.message}")
        }
        stopServer = null
        running = false
        appLog("Server stopped")
    }

    // The phone shouts "PCREMOTE_DISCOVER"; we answer "PCREMOTE_HERE:<pc name>"
    private fun startDiscovery() {
        try {
            val socket = DatagramSocket(DISCOVERY_PORT)
            discoverySocket = socket
            thread(isDaemon = true) {
                val buffer = ByteArray(256)
                try {
                    while (!socket.isClosed) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        if (text == "PCREMOTE_DISCOVER") {
                            val name = InetAddress.getLocalHost().hostName
                            val reply = "PCREMOTE_HERE:$name".toByteArray()
                            socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                            appLog("Find PC request answered (${packet.address.hostAddress})")
                        }
                    }
                } catch (e: Exception) {
                    // the socket was closed because the server was stopped: this is normal
                }
            }
        } catch (e: Exception) {
            appLog("Warning: auto-discovery is unavailable (${e.message})")
        }
    }

    private fun stopDiscovery() {
        discoverySocket?.close()
        discoverySocket = null
    }
}

// ================= THE WINDOW (JETPACK COMPOSE) =================

// The activity log shown in the window. Compose redraws the list automatically when it changes.
val logLines = mutableStateListOf<String>()

fun addLog(message: String) {
    val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    synchronized(logLines) {
        logLines.add("[$time] $message")
        if (logLines.size > 500) logLines.removeAt(0)   // keep the list from growing forever
    }
}

// Draws a QR code for `text` (used so a phone can open the web version by scanning)
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    }
    Canvas(modifier) {
        val cell = size.width / matrix.width
        drawRect(Color.White)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

/** A small trash can, drawn with lines (so no extra icon library is needed). */
@Composable
fun TrashIcon(color: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawLine(color, Offset(w * 0.15f, h * 0.25f), Offset(w * 0.85f, h * 0.25f), stroke, StrokeCap.Round)   // lid
        drawLine(color, Offset(w * 0.38f, h * 0.12f), Offset(w * 0.62f, h * 0.12f), stroke, StrokeCap.Round)   // handle
        drawLine(color, Offset(w * 0.25f, h * 0.25f), Offset(w * 0.32f, h * 0.9f), stroke, StrokeCap.Round)    // left side
        drawLine(color, Offset(w * 0.75f, h * 0.25f), Offset(w * 0.68f, h * 0.9f), stroke, StrokeCap.Round)    // right side
        drawLine(color, Offset(w * 0.32f, h * 0.9f), Offset(w * 0.68f, h * 0.9f), stroke, StrokeCap.Round)     // bottom
        drawLine(color, Offset(w * 0.43f, h * 0.4f), Offset(w * 0.45f, h * 0.75f), stroke * 0.8f, StrokeCap.Round)
        drawLine(color, Offset(w * 0.57f, h * 0.4f), Offset(w * 0.55f, h * 0.75f), stroke * 0.8f, StrokeCap.Round)
    }
}

// The tabs at the top of the PC window (the same style as the section bar of the phone apps).
// busy(index) = true shows a small dot: internet on, a routine running, Guard mode on.
@Composable
fun PcTabBar(selected: Int, onSelect: (Int) -> Unit, busy: (Int) -> Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(OtcPanel)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf("Home", "Internet", "Routines", "Security", "Log").forEachIndexed { index, name ->
            val on = index == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) OtcAccent.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    tr(name),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (on) OtcAccent else OtcMuted,
                    maxLines = 1
                )
                if (busy(index)) {
                    Spacer(Modifier.width(6.dp))
                    Box(modifier = Modifier.size(7.dp).background(OtcAccent, CircleShape))
                }
            }
        }
    }
}

@Composable
fun ServerApp(controller: ServerController) {
    val scope = rememberCoroutineScope()

    // ----- state: when one of these changes, the screen updates by itself -----
    val running = controller.running
    var busy by remember { mutableStateOf(false) }
    var addresses by remember { mutableStateOf(localAddresses()) }
    var tokenText by remember { mutableStateOf(serverToken) }
    var qrIndex by remember { mutableStateOf(-1) }   // which address the QR code uses (-1 = automatic)
    var tunnelBusy by remember { mutableStateOf(false) }            // true while the tunnel is being started/downloaded
    var askDownload by remember { mutableStateOf(false) }           // show the "download cloudflared?" question
    var allowDanger by remember { mutableStateOf(Guard.allowDangerousOverInternet) }
    var allowScreen by remember { mutableStateOf(Guard.allowScreenView) }
    var dialogMessage by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var guardPassword by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    // "12 s ago" in the devices list: the time is refreshed every 5 seconds
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            clock = System.currentTimeMillis()
            Devices.publish(clock)
        }
    }                       // 0 Home, 1 Internet, 2 Routines, 3 Security, 4 Log
    var confirmRoutine by remember { mutableStateOf<Routine?>(null) }   // "run this risky routine?" question
    var deleteAsk by remember { mutableStateOf<Routine?>(null) }        // "delete this routine?" question

    // recording a routine: countdown (3, 2, 1) -> recording -> finished list waiting for a name
    var recordCountdown by remember { mutableStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var recordCount by remember { mutableStateOf(0) }
    var recorded by remember { mutableStateOf<List<RecEvent>?>(null) }
    var recordName by remember { mutableStateOf("") }

    val lines = logLines.toList()
    val listState = rememberLazyListState()

    // Refresh the address list every 5 seconds (it changes when you switch Wi-Fi or turn on the hotspot)
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            addresses = withContext(Dispatchers.IO) { localAddresses() }
        }
    }

    // Scroll the log to the newest line (only while the Log tab is open)
    LaunchedEffect(lines.size, tab) {
        if (tab == 4 && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painter = rememberAppIcon(), contentDescription = null, modifier = Modifier.size(36.dp))
            Column {
                Text("One Touch Controller", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(tr("PC server"), style = MaterialTheme.typography.labelMedium, color = OtcMuted)
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (running) OtcAccent else OtcMuted, CircleShape)
            )
            Text(
                if (running) tr("Running (port %1)", PORT) else tr("Stopped"),
                style = MaterialTheme.typography.labelLarge,
                color = OtcMuted
            )
            OutlinedButton(shape = OtcKeyShape, onClick = { showSettings = true }) { Text(tr("Settings")) }
        }
        if (showSettings) SettingsDialog { showSettings = false }

        PcTabBar(
            selected = tab,
            onSelect = { tab = it },
            busy = { index ->
                when (index) {
                    1 -> tunnel.active
                    2 -> runningRoutines.isNotEmpty() || recording
                    3 -> GuardMode.active
                    else -> false
                }
            }
        )

        // one tab at a time; the long ones can be scrolled
        when (tab) {
            0 ->             Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ----- status + start/stop button -----
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().background(OtcPanel, OtcCardShape).padding(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(if (running) OtcAccent else OtcMuted, CircleShape)
                    )
                    Text(
                        if (running) tr("Running (port %1)", PORT) else tr("Stopped"),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.weight(1f))
                    Button(
                        shape = OtcKeyShape,
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                // start/stop run in the background so the window never freezes
                                val error = toggleServer(controller)
                                if (error != null) {
                                    appLog("Start failed: $error")
                                    dialogMessage = tr("Could not start the server:\n%1\n\nIs another copy of the server already running on port %2?", error, PORT)
                                }
                                addresses = localAddresses()
                                busy = false
                            }
                        }
                    ) {
                        Text(if (running) tr("Stop server") else tr("Start server"))
                    }
                }

                // ----- PC address -----
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().background(OtcPanel, OtcCardShape).padding(14.dp)
                ) {
                    Text(
                        tr("Type this IP in the phone app (or tap Find PC):"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (addresses.isEmpty()) {
                        Text(
                            tr("No network found. Connect to Wi-Fi or turn on the Mobile hotspot."),
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        addresses.forEach { address ->
                            Text(address, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // ----- web version (iPhone / any browser) -----
                val ips = addresses.map { it.substringBefore(" ") }   // just the IP, without the adapter name
                if (ips.isNotEmpty()) {
                    // automatic choice: the Windows Mobile Hotspot address (192.168.137.x) if there is one, else the first
                    val autoIndex = ips.indexOfFirst { it.startsWith("192.168.137.") }.let { if (it >= 0) it else 0 }
                    val chosen = if (qrIndex in ips.indices) qrIndex else autoIndex
                    val webUrl = "http://${ips[chosen]}:$PORT/app"
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().background(OtcPanel, OtcCardShape).padding(14.dp)
                    ) {
                        QrCode(webUrl, Modifier.size(110.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(tr("iPhone / any browser: scan the code or open"), style = MaterialTheme.typography.bodyMedium)
                            Text(webUrl, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text(
                                tr("In Safari: Share > Add to Home Screen. You still need the access token below."),
                                style = MaterialTheme.typography.labelMedium
                            )
                            if (ips.size > 1) {
                                TextButton(onClick = { qrIndex = (chosen + 1) % ips.size }) {
                                    Text(tr("Wrong address? Use the next one (%1/%2)", chosen + 1, ips.size))
                                }
                            }
                        }
                    }
                }

                // ----- token -----
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = tokenText,
                        onValueChange = { tokenText = it },
                        label = { Text(tr("Access token")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(shape = OtcKeyShape, onClick = { tokenText = Guard.generateToken() }) {
                        Text(tr("Generate"))
                    }
                    Button(shape = OtcKeyShape, onClick = {
                        val newToken = tokenText.trim()
                        if (newToken.isEmpty()) {
                            dialogMessage = tr("The token cannot be empty.")
                        } else if (tunnel.active && newToken.length < MIN_REMOTE_TOKEN_LENGTH) {
                            dialogMessage = tr("Internet access is on, so the token must have at least %1 characters. Turn internet access off first, or press Generate.", MIN_REMOTE_TOKEN_LENGTH)
                        } else {
                            serverToken = newToken
                            saveToken()
                            appLog("Token updated")
                        }
                    }) {
                        Text(tr("Apply token"))
                    }
                }

                // ----- presentation mode -----
                Surface(shape = OtcCardShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tr("Presentation mode"), style = MaterialTheme.typography.titleSmall)
                            Text(
                                tr("Shows every action from a phone in the bottom-right corner of this screen, so an audience can follow along."),
                                style = MaterialTheme.typography.bodyMedium,
                                color = OtcMuted
                            )
                        }
                        Switch(checked = Presenter.enabled, onCheckedChange = {
                            Presenter.enabled = it
                            appLog(if (it) "Presentation mode on" else "Presentation mode off")
                        })
                    }
                }

                // ----- connected devices -----
                Surface(shape = OtcCardShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("Connected devices") + " (" + Devices.list.size + ")", style = MaterialTheme.typography.titleSmall)
                        if (Devices.list.isEmpty()) {
                            Text(tr("No phone has connected yet."), style = MaterialTheme.typography.bodyMedium, color = OtcMuted)
                        }
                        Devices.list.forEach { d ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(OtcInk, OtcKeyShape)
                                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                            ) {
                                Box(modifier = Modifier.size(10.dp).background(if (d.live || clock - d.lastSeen < 30_000) OtcAccent else OtcMuted, CircleShape))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(tr(d.app), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        d.id + "   ·   " + (if (d.viaInternet) tr("Internet") else tr("Same network")) + "   ·   " + agoText(clock - d.lastSeen),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = OtcMuted
                                    )
                                }
                                OutlinedButton(shape = OtcKeyShape, onClick = {
                                    Devices.disconnect(d.id)
                                    appLog("Disconnected ${d.id} (refused for 10 minutes)")
                                }) { Text(tr("Disconnect")) }
                            }
                        }
                        Text(
                            tr("A disconnected device is refused for 10 minutes. To keep it out for good, change the token."),
                            style = MaterialTheme.typography.labelMedium,
                            color = OtcMuted
                        )
                    }
                }
            }
            1 ->             Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ----- internet access (Cloudflare tunnel) -----
                Surface(
                    shape = OtcCardShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(tr("Internet access (Cloudflare tunnel)"), style = MaterialTheme.typography.titleSmall)
                                Text(tr(tunnel.status), style = MaterialTheme.typography.bodyMedium)
                            }
                            Switch(
                                checked = tunnel.active,
                                enabled = !tunnelBusy && running,
                                onCheckedChange = { wantOn ->
                                    if (!wantOn) {
                                        scope.launch(Dispatchers.IO) { tunnel.stop() }
                                    } else if (serverToken.length < MIN_REMOTE_TOKEN_LENGTH) {
                                        dialogMessage = tr("Internet access needs an access token of at least %1 characters, because anyone on the internet could try to guess it.\n\nPress \"Generate\", then \"Apply token\", and enter the new token in your phone app / web page.", MIN_REMOTE_TOKEN_LENGTH)
                                    } else {
                                        scope.launch {
                                            tunnelBusy = true
                                            try {
                                                val exe = withContext(Dispatchers.IO) { tunnel.findCloudflared() }
                                                if (exe == null) askDownload = true
                                                else withContext(Dispatchers.IO) { tunnel.start(exe) }
                                            } catch (e: Exception) {
                                                dialogMessage = tr("Could not start the tunnel:\n%1", e.message)
                                            }
                                            tunnelBusy = false
                                        }
                                    }
                                }
                            )
                        }

                        if (!running) {
                            Text(tr("Start the server first."), style = MaterialTheme.typography.labelMedium)
                        } else if (serverToken.length < MIN_REMOTE_TOKEN_LENGTH) {
                            Text(
                                tr("Needs an access token with at least %1 characters (press Generate, then Apply token).", MIN_REMOTE_TOKEN_LENGTH),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        val publicUrl = tunnel.url
                        if (publicUrl != null) {
                            val appUrl = "$publicUrl/app"
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                QrCode(appUrl, Modifier.size(100.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(tr("Open on any phone, any network:"), style = MaterialTheme.typography.bodyMedium)
                                    Text(appUrl, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(
                                        tr("Android app: type %1 in the IP box. You still need the access token.", publicUrl),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    OutlinedButton(shape = OtcKeyShape, onClick = {
                                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(publicUrl), null)
                                    }) { Text(tr("Copy address")) }
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = allowDanger,
                                onCheckedChange = {
                                    allowDanger = it
                                    Guard.allowDangerousOverInternet = it
                                    appLog(if (it) "Shutdown/restart/scripts are now ALLOWED over the internet"
                                    else tr("Shutdown/restart/scripts are blocked over the internet"))
                                }
                            )
                            Text(tr("Allow shutdown, restart and scripts over the internet"), style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = allowScreen,
                                onCheckedChange = {
                                    allowScreen = it
                                    Guard.allowScreenView = it
                                    appLog(if (it) "Screen viewing is now ALLOWED" else "Screen viewing is turned off")
                                }
                            )
                            Text(tr("Allow viewing this PC's screen from a phone"), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (ScreenState.viewers > 0) {
                            Text(
                                tr("A phone is viewing this PC's screen right now"),
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        Text(
                            tr("Anyone who has the address AND the token can control this PC. The address changes every time you turn this on."),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            2 ->             Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ----- the routines, each with its own Run button -----
                Surface(
                    shape = OtcCardShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(tr("Automation routines"), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    tr("Run a routine here or from your phone."),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = OtcMuted
                                )
                            }
                            OutlinedButton(shape = OtcKeyShape, onClick = {
                                try {
                                    ProcessBuilder("notepad.exe", RoutineStore.file.absolutePath).start()
                                } catch (e: Exception) {
                                    dialogMessage = tr("Could not open Notepad. The file is here:\n%1", RoutineStore.file.absolutePath)
                                }
                            }) { Text(tr("Edit file")) }
                            OutlinedButton(shape = OtcKeyShape, onClick = { reloadRoutines() }) { Text(tr("Reload")) }
                        }
                        if (RoutineStore.routines.isEmpty()) {
                            Text(
                                tr("No routines yet. Press \"Edit file\" to write one, or record a task below."),
                                style = MaterialTheme.typography.bodyMedium,
                                color = OtcMuted
                            )
                        }
                        RoutineStore.routines.forEach { routine ->
                            val isRunning = routine.id in runningRoutines
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(OtcInk, OtcKeyShape)
                                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(routine.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        tr("%1 steps", routine.steps.size) + ": " + routine.steps.take(3).joinToString(", ") { describeStep(it) } +
                                                (if (routine.steps.size > 3) ", ..." else ""),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = OtcMuted,
                                        maxLines = 1
                                    )
                                }
                                Button(
                                    shape = OtcKeyShape,
                                    enabled = !isRunning,
                                    onClick = {
                                        if (routine.isDangerous(Guard.dangerousOverInternet)) confirmRoutine = routine
                                        else startRoutine(routine, "PC window")
                                    }
                                ) { Text(if (isRunning) tr("Running...") else tr("Run")) }
                                // delete (trash can)
                                IconButton(enabled = !isRunning, onClick = { deleteAsk = routine }) {
                                    TrashIcon(if (isRunning) OtcMuted.copy(alpha = 0.4f) else OtcDanger)
                                }
                            }
                        }
                        RoutineStore.problems.take(3).forEach {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                // ----- record a routine -----
                Surface(
                    shape = OtcCardShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // ----- record a routine: do the task once by hand, the PC repeats it later -----
                        Text(tr("Record a task"), style = MaterialTheme.typography.titleSmall)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                when {
                                    recording -> tr("Recording: %1 actions so far. Press F9 or Stop when the task is finished.", recordCount)
                                    recordCountdown > 0 -> tr("Recording starts in %1...", recordCountdown)
                                    else -> tr("Record a task once (mouse and keyboard) and run it again from here or from your phone.")
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (recording) OtcAccent else OtcMuted,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                shape = OtcKeyShape,
                                enabled = recording || (recordCountdown == 0 && recorded == null),
                                onClick = {
                                    if (recording) {                   // the Stop button: works even if F9 does not
                                        InputRecorder.stop(fromButton = true)
                                        return@Button
                                    }
                                    scope.launch {
                                        for (n in 3 downTo 1) { recordCountdown = n; delay(1000) }
                                        recordCountdown = 0
                                        try {
                                            recordCount = 0
                                            InputRecorder.start(
                                                progress = { recordCount = it },
                                                stopped = { events -> recording = false; recorded = events; recordName = "" }
                                            )
                                            recording = true
                                            appLog("Recording started (F9 stops it)")
                                        } catch (e: Throwable) {
                                            dialogMessage = tr("Could not start recording:\n%1", e.message ?: e.javaClass.simpleName)
                                        }
                                    }
                                }
                            ) { Text(if (recording) tr("Stop") else tr("Record")) }
                        }
                    }
                }
            }
            3 ->             Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ----- Guard mode: block the PC's own mouse and keyboard while you are away -----
                Surface(
                    shape = OtcCardShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(tr("Guard mode"), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    when {
                                        GuardMode.active -> tr("On: the PC's own mouse and keyboard are blocked. Your phone still works.")
                                        GuardMode.hasPassword -> tr("Off. Turn it on from your phone when you leave the PC.")
                                        else -> tr("Set a Guard password first.")
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (GuardMode.active) OtcAccent else OtcMuted
                                )
                            }
                            Button(
                                shape = OtcKeyShape,
                                enabled = GuardMode.hasPassword || GuardMode.active,
                                onClick = {
                                    try {
                                        if (GuardMode.active) GuardMode.turnOff("from the PC window") else GuardMode.turnOn("from the PC window")
                                    } catch (e: Exception) {
                                        dialogMessage = tr(e.message ?: "Guard mode could not start")
                                    }
                                }
                            ) { Text(if (GuardMode.active) tr("Turn off") else tr("Turn on")) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = guardPassword,
                                onValueChange = { guardPassword = it.filter { c -> c.isLetterOrDigit() }.take(GuardPassword.MAX_LENGTH) },
                                label = { Text(if (GuardMode.hasPassword) tr("New Guard password") else tr("Guard password")) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(shape = OtcKeyShape, onClick = {
                                val problem = GuardMode.setPassword(guardPassword)
                                if (problem == null) {
                                    guardPassword = ""
                                    appLog("Guard password saved")
                                    dialogMessage = tr("Guard password saved.")
                                } else {
                                    dialogMessage = problem
                                }
                            }) { Text(tr("Save password")) }
                        }
                        Text(
                            tr("Letters and numbers, 4 to 32, capital letters do not matter. Anyone at the PC must type it to unlock. Ctrl+Alt+Del and holding the power button cannot be blocked by any program."),
                            style = MaterialTheme.typography.labelMedium,
                            color = OtcMuted
                        )
                    }
                }
            }
            else -> Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ----- activity log -----
                Text(tr("Activity log"), style = MaterialTheme.typography.titleSmall)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    shape = OtcCardShape,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        items(lines.size) { index ->
                            Text(
                                lines[index],
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                OutlinedButton(
                    shape = OtcKeyShape,
                    onClick = { synchronized(logLines) { logLines.clear() } },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(tr("Clear log"))
                }
            }
        }
    }

    // ----- "run this routine?" for routines with shutdown / restart / script steps -----
    confirmRoutine?.let { routine ->
        AlertDialog(
            onDismissRequest = { confirmRoutine = null },
            title = { Text(tr("Are you sure?")) },
            text = { Text(tr("Run \"%1\" on this PC? It contains shutdown, restart or script steps.", routine.name)) },
            confirmButton = {
                TextButton(onClick = { startRoutine(routine, "PC window"); confirmRoutine = null }) { Text(tr("Run")) }
            },
            dismissButton = { TextButton(onClick = { confirmRoutine = null }) { Text(tr("Cancel")) } }
        )
    }

    // ----- "delete this routine?" -----
    deleteAsk?.let { routine ->
        val recorded = routine.steps.any { it is Step.Replay }
        AlertDialog(
            onDismissRequest = { deleteAsk = null },
            title = { Text(tr("Delete \"%1\"?", routine.name)) },
            text = {
                Text(
                    if (recorded) tr("The routine and its recording are deleted. This cannot be undone.")
                    else tr("The routine is removed from the routines file. This cannot be undone.")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteAsk = null
                    deleteRoutine(routine)?.let { dialogMessage = it }
                }) { Text(tr("Delete"), color = OtcDanger) }
            },
            dismissButton = { TextButton(onClick = { deleteAsk = null }) { Text(tr("Cancel")) } }
        )
    }

    // ----- "download cloudflared?" question -----
    // ----- a recording was finished: ask for a name and save it as a routine -----
    recorded?.let { events ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (events.isEmpty()) tr("Nothing was recorded") else tr("Save this routine?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (events.isEmpty()) {
                        Text(tr("No mouse or keyboard action arrived before F9 was pressed."))
                    } else {
                        Text(tr("%1 actions, about %2 seconds. It will appear in the Routines list of the remote apps.", events.size, recordingSeconds(events)))
                        OutlinedTextField(
                            value = recordName,
                            onValueChange = { recordName = it.replace("\n", " ").take(40) },
                            label = { Text(tr("Name of the routine")) },
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                if (events.isNotEmpty()) TextButton(
                    enabled = recordName.isNotBlank(),
                    onClick = {
                        try {
                            val name = recordName.trim()
                            val taken = RoutineStore.routines.map { it.id }.toSet() +
                                    (InputRecorder.folder.list()?.map { it.removeSuffix(".rec") } ?: emptyList())
                            val id = recordingId(name, taken)
                            InputRecorder.save(id, events)
                            RoutineStore.file.parentFile?.mkdirs()
                            RoutineStore.file.appendText("\n[$id] $name\nreplay $id.rec\n")
                            reloadRoutines()
                            appLog("Saved the recorded routine \"$name\"")
                        } catch (e: Exception) {
                            dialogMessage = tr("Could not save the routine:\n%1", e.message)
                        }
                        recorded = null
                    }
                ) { Text(tr("Save")) }
            },
            dismissButton = {
                TextButton(onClick = { recorded = null }) { Text(if (events.isEmpty()) tr("OK") else tr("Discard")) }
            }
        )
    }

    if (askDownload) {
        AlertDialog(
            onDismissRequest = { askDownload = false },
            title = { Text(tr("Download cloudflared?")) },
            text = {
                Text(
                    tr("Internet access needs the free program \"cloudflared\" from Cloudflare (about 55 MB).\n\nIt will be downloaded from github.com/cloudflare/cloudflared and saved in your user folder (.onetouch). Download it now?")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askDownload = false
                    scope.launch {
                        tunnelBusy = true
                        try {
                            withContext(Dispatchers.IO) { tunnel.start(tunnel.download()) }
                        } catch (e: Exception) {
                            dialogMessage = tr("Download failed:\n%1", e.message)
                        }
                        tunnelBusy = false
                    }
                }) { Text(tr("Download")) }
            },
            dismissButton = { TextButton(onClick = { askDownload = false }) { Text(tr("Cancel")) } }
        )
    }

    // ----- message pop-up (errors) -----
    dialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { dialogMessage = null },
            title = { Text(tr("Notice")) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { dialogMessage = null }) { Text(tr("OK")) }
            }
        )
    }
}

// ================= DESIGN (the same look as the phone app and the web version) =================
// (the colours OtcInk, OtcPanel, OtcAccent, ... are in Settings.kt, because they follow the settings)

val OtcKeyShape = RoundedCornerShape(12.dp)
val OtcCardShape = RoundedCornerShape(16.dp)

val OtcPcShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

// ================= ICON (drawn with code, used for the window, the taskbar and the tray) =================
/** A tile in [tile] colour with a power symbol in [ink] colour. */
fun appIconPainter(tile: Color, ink: Color): Painter = object : Painter() {
    override val intrinsicSize: Size = Size(256f, 256f)

    override fun DrawScope.onDraw() {
        val d = size.minDimension
        val w = d / 11f
        drawRoundRect(tile, cornerRadius = CornerRadius(d / 4.2f))
        drawArc(
            color = ink, startAngle = -60f, sweepAngle = 300f, useCenter = false,
            topLeft = Offset(d * 0.26f, d * 0.28f), size = Size(d * 0.48f, d * 0.48f),
            style = Stroke(width = w, cap = StrokeCap.Round)
        )
        drawLine(ink, Offset(d / 2f, d * 0.2f), Offset(d / 2f, d * 0.5f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

/** The icon in the accent colour chosen in Settings. It is drawn again when the colour or the theme changes. */
@Composable
fun rememberAppIcon(): Painter {
    val tile = OtcAccent
    val ink = OtcAccentInk
    return remember(tile, ink) { appIconPainter(tile, ink) }
}

// Starts the server if it is stopped, stops it if it is running.
// Returns an error message, or null if everything went fine. Runs in the background.
suspend fun toggleServer(controller: ServerController): String? = withContext(Dispatchers.IO) {
    try {
        if (controller.running) controller.stop() else controller.start()
        null
    } catch (e: Exception) {
        e.message ?: "Unknown error"
    }
}

// ================= START THE PROGRAM =================
fun main() {
    Look.load()      // theme, accent colour and language of the window
    System.setProperty("java.awt.headless", "false") // needed so Robot and the window work
    loadToken()
    reloadRoutines()
    appLog = { message -> addLog(message) }
    GuardMode.load()     // Guard password; also repairs the power button if the program was closed while guarding
    val controller = ServerController()

    // The server starts automatically. You can still stop it with the button or the tray menu.
    try {
        controller.start()
    } catch (e: Exception) {
        appLog("Could not start automatically: ${e.message}")
    }

    application {
        val scope = rememberCoroutineScope()
        val trayState = rememberTrayState()
        var windowVisible by remember { mutableStateOf(true) }
        var hintShown by remember { mutableStateOf(false) }

        fun exitProgram() {
            tunnel.stop()          // close the internet tunnel first
            controller.stop()      // stop the server properly, then close
            exitApplication()
        }

        // the same icon in the window title, the taskbar and the tray (follows the accent colour)
        val icon = rememberAppIcon()

        // ----- system tray icon (next to the clock) -----
        if (isTraySupported) {
            Tray(
                icon = icon,
                state = trayState,
                tooltip = if (controller.running) tr("One Touch Controller - running") else tr("One Touch Controller - stopped"),
                onAction = { windowVisible = true },   // click on the icon = open the window
                menu = {
                    Item(tr("Open window"), onClick = { windowVisible = true })
                    Item(
                        if (controller.running) tr("Stop server") else tr("Start server"),
                        onClick = {
                            scope.launch {
                                val error = toggleServer(controller)
                                if (error != null) appLog("Start failed: $error")
                            }
                        }
                    )
                    Separator()
                    Item(tr("Exit"), onClick = { exitProgram() })
                }
            )
        }

        // ----- the window -----
        Window(
            onCloseRequest = {
                if (isTraySupported) {
                    windowVisible = false      // closing the window only hides it; the server keeps running
                    if (!hintShown) {
                        hintShown = true
                        trayState.sendNotification(
                            Notification(
                                "One Touch Controller",
                                tr("Still running in the system tray. Right-click the icon to exit.")
                            )
                        )
                    }
                } else {
                    exitProgram()
                }
            },
            visible = windowVisible,
            title = "One Touch Controller - PC Server",
            icon = icon,
            state = rememberWindowState(width = 720.dp, height = 780.dp)
        ) {
            MaterialTheme(colorScheme = otcPcColorScheme(), shapes = OtcPcShapes) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ServerApp(controller)
                }
            }
        }
    }
}