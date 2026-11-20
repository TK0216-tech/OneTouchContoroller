package jp.relative.tech.one_touch_controller.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import jp.relative.tech.one_touch_controller.domain.config.AppLogger
import jp.relative.tech.one_touch_controller.domain.config.DEFAULT_SERVER_PORT
import jp.relative.tech.one_touch_controller.domain.ports.TunnelPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Starts and stops the HTTP/WebSocket server and the UDP auto-discovery listener together.
 */
class ServerController(
    val port: Int = DEFAULT_SERVER_PORT,
    private val deps: RemoteServerDeps,
    private val discoveryService: DiscoveryService,
    private val tunnel: TunnelPort,
    private val logger: AppLogger
) {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var stopServer: (() -> Unit)? = null

    fun start() {
        if (_running.value) return
        val s = embeddedServer(Netty, port = port, host = "0.0.0.0") { remoteModule(deps) }
        s.start(wait = false)              // wait = false -> the program keeps running (the window stays usable)
        stopServer = { s.stop(500, 1000) }
        discoveryService.start()
        _running.value = true
        logger.log("Server started on port $port")
    }

    fun stop() {
        if (!_running.value) return
        tunnel.stop()          // no server = nothing for the internet tunnel to connect to
        discoveryService.stop()
        try {
            stopServer?.invoke()
        } catch (e: Exception) {
            logger.log("Error while stopping: ${e.message}")
        }
        stopServer = null
        _running.value = false
        logger.log("Server stopped")
    }

    /**
     * Starts the server if it is stopped, stops it if it is running.
     * Returns an error message, or null if everything went fine.
     */
    suspend fun toggle(): String? = withContext(Dispatchers.IO) {
        try {
            if (_running.value) stop() else start()
            null
        } catch (e: Exception) {
            e.message ?: "Unknown error"
        }
    }
}
