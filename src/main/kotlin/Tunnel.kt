// Internet access through a free Cloudflare "quick tunnel".
//
// How it works: the small program "cloudflared" (made by Cloudflare) makes an OUTGOING connection from this
// PC to Cloudflare and gets a public https address like https://random-words.trycloudflare.com.
// Anything sent to that address is passed to this server (http://127.0.0.1:8080).
// No router settings and no firewall rules are needed, and it works on any Wi-Fi or mobile data.
// The address is new every time the tunnel is started.

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

const val CLOUDFLARED_URL =
    "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"

// Internet access is refused unless the token is at least this long (a short token could be guessed)
const val MIN_REMOTE_TOKEN_LENGTH = 16

class TunnelController {
    var status by mutableStateOf("Off")          // text shown in the window
        private set
    var url by mutableStateOf<String?>(null)     // the public address, once Cloudflare has given one
        private set
    var online by mutableStateOf(false)          // true when the tunnel is connected and usable
        private set
    var active by mutableStateOf(false)          // true from "Start" until "Stop" (also while connecting)
        private set

    private var process: Process? = null
    private val folder = File(System.getProperty("user.home"), ".onetouch")
    private val ownCopy = File(folder, "cloudflared.exe")

    init {
        // if the program is closed in any way, do not leave cloudflared running in the background
        Runtime.getRuntime().addShutdownHook(Thread { process?.destroyForcibly() })
    }

    // Looks for cloudflared: first our own download, then anything already installed on the PC
    fun findCloudflared(): File? {
        if (ownCopy.isFile) return ownCopy
        return try {
            val p = ProcessBuilder("where", "cloudflared").redirectErrorStream(true).start()
            val line = p.inputStream.bufferedReader().readLines().firstOrNull { it.isNotBlank() }?.trim()
            p.waitFor()
            if (p.exitValue() == 0 && line != null && File(line).isFile) File(line) else null
        } catch (e: Exception) {
            null
        }
    }

    // Downloads cloudflared from the official GitHub page (about 55 MB). Blocking: call from a background thread.
    fun download(): File {
        try {
            folder.mkdirs()
            val tmp = File(folder, "cloudflared.exe.part")
            status = "Downloading cloudflared..."
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(20))
                .build()
            val response = client.send(
                HttpRequest.newBuilder(URI.create(CLOUDFLARED_URL)).build(),
                HttpResponse.BodyHandlers.ofInputStream()
            )
            if (response.statusCode() != 200) throw RuntimeException("Download failed (HTTP ${response.statusCode()})")
            val total = response.headers().firstValueAsLong("content-length").orElse(-1L)

            response.body().use { input ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done - shown > 1_000_000) {
                            shown = done
                            status = if (total > 0) "Downloading cloudflared... ${done * 100 / total}%"
                            else "Downloading cloudflared... ${done / 1_000_000} MB"
                        }
                    }
                }
            }

            // sanity check: a Windows program is several MB big and starts with the letters "MZ"
            val looksValid = tmp.length() > 10_000_000 &&
                    tmp.inputStream().use { it.read() == 'M'.code && it.read() == 'Z'.code }
            if (!looksValid) {
                tmp.delete()
                throw RuntimeException("The downloaded file is not a valid program")
            }
            if (ownCopy.exists()) ownCopy.delete()
            if (!tmp.renameTo(ownCopy)) throw RuntimeException("Could not save cloudflared in ${folder.absolutePath}")
            appLog("cloudflared downloaded to ${ownCopy.absolutePath}")
            return ownCopy
        } catch (e: Exception) {
            status = "Off"
            throw e
        }
    }

    // Starts the tunnel. Returns right away; the window updates itself as the state changes.
    fun start(exe: File) {
        stop()
        url = null
        online = false
        active = true
        status = "Starting the tunnel..."

        val p = ProcessBuilder(exe.absolutePath, "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:$PORT")
            .redirectErrorStream(true)
            .start()
        process = p

        thread(isDaemon = true) {
            var lastError = ""
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    // cloudflared prints the new public address; "api.trycloudflare.com" is Cloudflare itself, not ours
                    val match = Regex("https://([a-z0-9-]+)\\.trycloudflare\\.com").find(line)
                    if (match != null && match.groupValues[1] != "api" && url == null) {
                        url = match.value
                        if (!online) status = "Connecting..."
                    }
                    if (line.contains("Registered tunnel connection") && !online) {
                        online = true
                        status = "Online"
                        appLog("Internet access is ON")
                    }
                    if (line.contains(" ERR ")) lastError = line.substringAfter(" ERR ").trim().take(160)
                }
            } catch (e: Exception) {
                // the process was stopped: normal
            }
            p.waitFor()
            // if nobody pressed "stop", cloudflared ended by itself: tell the user why
            if (process === p) {
                process = null
                active = false
                online = false
                url = null
                status = "Stopped: cloudflared ended" + if (lastError.isNotEmpty()) " ($lastError)" else ""
                appLog(status)
            }
        }

        // if no address arrives for a long time, say so instead of staying silent
        thread(isDaemon = true) {
            Thread.sleep(45_000)
            if (process === p && url == null) status = "Still waiting for an address. Check the PC's internet connection."
        }
    }

    // Stops the tunnel (safe to call when it is not running)
    fun stop() {
        val p = process
        process = null
        if (p != null) {
            p.destroy()
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
            appLog("Internet access is OFF")
        }
        active = false
        online = false
        url = null
        status = "Off"
    }
}

val tunnel = TunnelController()