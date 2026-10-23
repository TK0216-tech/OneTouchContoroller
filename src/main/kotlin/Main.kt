import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.concurrent.thread

// ================= SETTINGS =================
const val PORT = 8080                  // HTTP port for commands (the app uses 8080)
const val DISCOVERY_PORT = 8888        // UDP port for auto-discovery

@Volatile var serverToken = "9999"          // can be changed in the window
@Volatile var appLog: (String) -> Unit = { println(it) }  // the window replaces this with its log box

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
    }
}

// ================= SERVER ON/OFF CONTROL =================
// Starts and stops the HTTP server and the auto-discovery listener together.
class ServerController {
    var running = false
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

// ================= THE WINDOW (GUI) =================
class ServerWindow : JFrame("One Touch Controller - PC Server") {
    private val controller = ServerController()

    private val statusLabel = JLabel()
    private val toggleButton = JButton("Start server")
    private val addressLabel = JLabel()
    private val tokenField = JTextField(16)
    private val applyButton = JButton("Apply token")
    private val logArea = JTextArea()

    init {
        loadToken()
        tokenField.text = serverToken
        appLog = { message -> appendLog(message) }

        // ----- top part: status, addresses, token -----
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(8, 8, 4, 8)
        }
        statusLabel.font = statusLabel.font.deriveFont(Font.BOLD, 15f)
        top.add(row(statusLabel, toggleButton))
        top.add(row(JLabel("Type this IP in the phone app (or tap Find PC):")))
        top.add(row(addressLabel))
        top.add(row(JLabel("Access token:"), tokenField, applyButton))

        // ----- middle part: log -----
        logArea.isEditable = false
        logArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        val logPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
            add(JLabel("Activity log:"), BorderLayout.NORTH)
            add(JScrollPane(logArea), BorderLayout.CENTER)
        }

        // ----- bottom part -----
        val clearButton = JButton("Clear log")
        clearButton.addActionListener { logArea.text = "" }
        val bottom = JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(clearButton) }

        add(top, BorderLayout.NORTH)
        add(logPanel, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)

        // ----- button actions -----
        toggleButton.addActionListener { toggleServer() }
        applyButton.addActionListener {
            val newToken = tokenField.text.trim()
            if (newToken.isEmpty()) {
                JOptionPane.showMessageDialog(this, "The token cannot be empty.")
                return@addActionListener
            }
            serverToken = newToken
            saveToken()
            appLog("Token updated")
        }

        // Stop the server properly when the window is closed
        defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                controller.stop()
            }
        })

        // Refresh the address list every 5 seconds (it changes when you switch Wi-Fi or turn on the hotspot)
        javax.swing.Timer(5000) { refreshAddresses() }.start()

        refreshAddresses()
        updateState()
        setSize(580, 460)
        setLocationRelativeTo(null)
    }

    // A small helper that puts components on one line
    private fun row(vararg parts: java.awt.Component): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            parts.forEach { add(it) }
        }

    private fun toggleServer() {
        if (controller.running) {
            controller.stop()
        } else {
            try {
                controller.start()
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this,
                    "Could not start the server:\n${e.message}\n\nIs another copy of the server already running on port $PORT?",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
                appLog("Start failed: ${e.message}")
            }
        }
        refreshAddresses()
        updateState()
    }

    private fun updateState() {
        if (controller.running) {
            statusLabel.text = "●  Running (port $PORT)"
            statusLabel.foreground = Color(0, 150, 0)
            toggleButton.text = "Stop server"
        } else {
            statusLabel.text = "●  Stopped"
            statusLabel.foreground = Color.GRAY
            toggleButton.text = "Start server"
        }
    }

    private fun refreshAddresses() {
        val list = localAddresses()
        addressLabel.text = if (list.isEmpty()) {
            "No network found. Connect to Wi-Fi or turn on the Mobile hotspot."
        } else {
            "<html>" + list.joinToString("<br>") + "</html>"
        }
    }

    // Can be called from any thread (for example from the server threads)
    private fun appendLog(message: String) {
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        SwingUtilities.invokeLater {
            logArea.append("[$time] $message\n")
            logArea.caretPosition = logArea.document.length
        }
    }
}

// ================= START THE PROGRAM =================
fun main() {
    System.setProperty("java.awt.headless", "false") // needed so Robot and the window work
    try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) // Windows-style look
    } catch (e: Exception) {
        // keep the default look
    }
    SwingUtilities.invokeLater {
        ServerWindow().isVisible = true
    }
}