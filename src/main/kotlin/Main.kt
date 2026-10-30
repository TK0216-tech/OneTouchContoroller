import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    if (request.headers["X-Auth-Token"] == serverToken) return true
    appLog("Rejected a request: wrong token")
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
    "shutdown_60" to { startProcess("shutdown", "/s", "/t", "60"); "Shutting down in 60 seconds" },
    "restart_60" to { startProcess("shutdown", "/r", "/t", "60"); "Restarting in 60 seconds" },
    "cancel_shutdown" to { startProcess("shutdown", "/a"); "Shutdown/restart cancelled" },
)

// ================= SERVER ROUTES =================
fun Application.remoteModule() {
    routing {
        get("/") {
            call.respondText("PC Remote server is running")
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
            appLog("Command: $name")
            val message = try {
                action()
            } catch (e: Exception) {
                appLog("Command failed: ${e.message}")
                call.respondText("Failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                return@post
            }
            call.respondText(message)
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

        // ---- Touchpad: click ("left" or "right") ----
        post("/mouse/click") {
            if (!call.checkToken()) return@post
            val button = if (jsonField(call.receiveText(), "button") == "right")
                InputEvent.BUTTON3_DOWN_MASK else InputEvent.BUTTON1_DOWN_MASK
            robot.mousePress(button)
            robot.mouseRelease(button)
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

@Composable
fun ServerApp(controller: ServerController) {
    val scope = rememberCoroutineScope()

    // ----- state: when one of these changes, the screen updates by itself -----
    val running = controller.running
    var busy by remember { mutableStateOf(false) }
    var addresses by remember { mutableStateOf(localAddresses()) }
    var tokenText by remember { mutableStateOf(serverToken) }
    var qrIndex by remember { mutableStateOf(-1) }   // which address the QR code uses (-1 = automatic)
    var dialogMessage by remember { mutableStateOf<String?>(null) }

    val lines = logLines.toList()
    val listState = rememberLazyListState()

    // Refresh the address list every 5 seconds (it changes when you switch Wi-Fi or turn on the hotspot)
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            addresses = withContext(Dispatchers.IO) { localAddresses() }
        }
    }

    // Scroll the log to the newest line
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "One Touch Controller",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // ----- status + start/stop button -----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(if (running) Color(0xFF2E9E4F) else Color.Gray, CircleShape)
            )
            Text(
                if (running) "Running (port $PORT)" else "Stopped",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.weight(1f))
            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        // start/stop run in the background so the window never freezes
                        val error = toggleServer(controller)
                        if (error != null) {
                            appLog("Start failed: $error")
                            dialogMessage = "Could not start the server:\n$error\n\n" +
                                    "Is another copy of the server already running on port $PORT?"
                        }
                        addresses = localAddresses()
                        busy = false
                    }
                }
            ) {
                Text(if (running) "Stop server" else "Start server")
            }
        }

        // ----- PC address -----
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Type this IP in the phone app (or tap Find PC):",
                style = MaterialTheme.typography.bodyMedium
            )
            if (addresses.isEmpty()) {
                Text(
                    "No network found. Connect to Wi-Fi or turn on the Mobile hotspot.",
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QrCode(webUrl, Modifier.size(110.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("iPhone / any browser: scan the code or open", style = MaterialTheme.typography.bodyMedium)
                    Text(webUrl, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(
                        "In Safari: Share > Add to Home Screen. You still need the access token below.",
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (ips.size > 1) {
                        TextButton(onClick = { qrIndex = (chosen + 1) % ips.size }) {
                            Text("Wrong address? Use the next one (${chosen + 1}/${ips.size})")
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
                label = { Text("Access token") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                val newToken = tokenText.trim()
                if (newToken.isEmpty()) {
                    dialogMessage = "The token cannot be empty."
                } else {
                    serverToken = newToken
                    saveToken()
                    appLog("Token updated")
                }
            }) {
                Text("Apply token")
            }
        }

        // ----- activity log -----
        Text("Activity log", style = MaterialTheme.typography.titleSmall)
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
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
            onClick = { synchronized(logLines) { logLines.clear() } },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Clear log")
        }
    }

    // ----- message pop-up (errors) -----
    dialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { dialogMessage = null },
            title = { Text("Notice") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { dialogMessage = null }) { Text("OK") }
            }
        )
    }
}

// ================= ICON (drawn with code, used for the window and the tray) =================
val appIcon: Painter = object : Painter() {
    override val intrinsicSize: Size = Size(256f, 256f)

    override fun DrawScope.onDraw() {
        drawRoundRect(Color(0xFF1E3A8A), cornerRadius = CornerRadius(size.minDimension / 5f))
        drawCircle(Color.White, radius = size.minDimension / 4f, style = Stroke(width = size.minDimension / 14f))
        drawCircle(Color.White, radius = size.minDimension / 12f)
    }
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
    System.setProperty("java.awt.headless", "false") // needed so Robot and the window work
    loadToken()
    appLog = { message -> addLog(message) }
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
            controller.stop()      // stop the server properly, then close
            exitApplication()
        }

        // ----- system tray icon (next to the clock) -----
        if (isTraySupported) {
            Tray(
                icon = appIcon,
                state = trayState,
                tooltip = if (controller.running) "One Touch Controller - running" else "One Touch Controller - stopped",
                onAction = { windowVisible = true },   // click on the icon = open the window
                menu = {
                    Item("Open window", onClick = { windowVisible = true })
                    Item(
                        if (controller.running) "Stop server" else "Start server",
                        onClick = {
                            scope.launch {
                                val error = toggleServer(controller)
                                if (error != null) appLog("Start failed: $error")
                            }
                        }
                    )
                    Separator()
                    Item("Exit", onClick = { exitProgram() })
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
                                "Still running in the system tray. Right-click the icon to exit."
                            )
                        )
                    }
                } else {
                    exitProgram()
                }
            },
            visible = windowVisible,
            title = "One Touch Controller - PC Server",
            icon = appIcon,
            state = rememberWindowState(width = 640.dp, height = 780.dp)
        ) {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ServerApp(controller)
                }
            }
        }
    }
}