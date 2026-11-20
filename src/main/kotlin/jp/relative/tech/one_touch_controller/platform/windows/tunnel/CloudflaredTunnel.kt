package jp.relative.tech.one_touch_controller.platform.windows.tunnel

import jp.relative.tech.one_touch_controller.domain.config.AppLogger
import jp.relative.tech.one_touch_controller.domain.config.DEFAULT_SERVER_PORT
import jp.relative.tech.one_touch_controller.domain.ports.TunnelPort
import jp.relative.tech.one_touch_controller.domain.ports.TunnelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

/**
 * Internet access through a free Cloudflare "quick tunnel" (`cloudflared`).
 *
 * `cloudflared` makes an OUTGOING connection from this PC to Cloudflare and gets a public https address
 * like `https://random-words.trycloudflare.com`. Anything sent to that address is passed to `http://127.0.0.1:<port>`.
 */
class CloudflaredTunnel(
    private val ownCopy: File,
    private val serverPort: Int = DEFAULT_SERVER_PORT,
    private val logger: AppLogger
) : TunnelPort {
    private val _state = MutableStateFlow(TunnelState())
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    @Volatile private var process: Process? = null
    private val folder: File = ownCopy.parentFile

    init {
        // if the program is closed in any way, do not leave cloudflared running in the background
        Runtime.getRuntime().addShutdownHook(Thread { process?.destroyForcibly() })
    }

    // Looks for cloudflared: first our own download, then anything already installed on the PC
    override fun findExecutable(): String? {
        if (ownCopy.isFile) return ownCopy.absolutePath
        return try {
            val p = ProcessBuilder("where", "cloudflared").redirectErrorStream(true).start()
            val line = p.inputStream.bufferedReader().readLines().firstOrNull { it.isNotBlank() }?.trim()
            p.waitFor()
            if (p.exitValue() == 0 && line != null && File(line).isFile) line else null
        } catch (e: Exception) {
            null
        }
    }

    // Downloads cloudflared from the official GitHub page (about 55 MB). Blocking: call from a background thread.
    override fun download(): String {
        try {
            folder.mkdirs()
            val tmp = File(folder, "cloudflared.exe.part")
            _state.update { it.copy(status = "Downloading cloudflared...") }
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
                            val progressText = if (total > 0) "Downloading cloudflared... ${done * 100 / total}%"
                            else "Downloading cloudflared... ${done / 1_000_000} MB"
                            _state.update { it.copy(status = progressText) }
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
            logger.log("cloudflared downloaded to ${ownCopy.absolutePath}")
            return ownCopy.absolutePath
        } catch (e: Exception) {
            _state.update { it.copy(status = "Off") }
            throw e
        }
    }

    // Starts the tunnel. Returns right away; state updates asynchronously.
    override fun start(executablePath: String) {
        stop()
        _state.value = TunnelState(status = "Starting the tunnel...", url = null, online = false, active = true)

        val p = ProcessBuilder(executablePath, "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:$serverPort")
            .redirectErrorStream(true)
            .start()
        process = p

        thread(isDaemon = true) {
            var lastError = ""
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    // cloudflared prints the new public address; "api.trycloudflare.com" is Cloudflare itself, not ours
                    val match = Regex("https://([a-z0-9-]+)\\.trycloudflare\\.com").find(line)
                    if (match != null && match.groupValues[1] != "api" && _state.value.url == null) {
                        _state.update { cur ->
                            cur.copy(
                                url = match.value,
                                status = if (!cur.online) "Connecting..." else cur.status
                            )
                        }
                    }
                    if (line.contains("Registered tunnel connection") && !_state.value.online) {
                        _state.update { it.copy(online = true, status = "Online") }
                        logger.log("Internet access is ON")
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
                val stoppedStatus = "Stopped: cloudflared ended" + if (lastError.isNotEmpty()) " ($lastError)" else ""
                _state.value = TunnelState(status = stoppedStatus, url = null, online = false, active = false)
                logger.log(stoppedStatus)
            }
        }

        // if no address arrives for a long time, say so instead of staying silent
        thread(isDaemon = true) {
            Thread.sleep(45_000)
            if (process === p && _state.value.url == null) {
                _state.update { it.copy(status = "Still waiting for an address. Check the PC's internet connection.") }
            }
        }
    }

    // Stops the tunnel (safe to call when it is not running)
    override fun stop() {
        val p = process
        process = null
        if (p != null) {
            p.destroy()
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
            logger.log("Internet access is OFF")
        }
        _state.value = TunnelState(status = "Off", url = null, online = false, active = false)
    }
}
