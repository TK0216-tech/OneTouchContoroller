package jp.relative.tech.one_touch_controller.server.routes

import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import jp.relative.tech.one_touch_controller.application.ScreenStreamState
import jp.relative.tech.one_touch_controller.domain.config.AppLogger
import jp.relative.tech.one_touch_controller.domain.ports.MouseButton
import jp.relative.tech.one_touch_controller.domain.ports.PcInputPort
import jp.relative.tech.one_touch_controller.domain.ports.ScreenCapturePort
import jp.relative.tech.one_touch_controller.domain.protocol.SocketCommand
import jp.relative.tech.one_touch_controller.domain.protocol.parseSocketCommand
import jp.relative.tech.one_touch_controller.domain.screen.viewRect
import jp.relative.tech.one_touch_controller.server.AuthSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

internal fun Route.webSocketRoutes(
    auth: AuthSupport,
    pcInput: PcInputPort,
    screenCapture: ScreenCapturePort,
    screenStreamState: ScreenStreamState,
    logger: AppLogger
) {
    // How many live connections (WebSockets) are open at the moment, including ones that have not logged in yet
    val liveConnections = AtomicInteger(0)

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
        try {
            val id = auth.clientId(call)
            val wait = auth.accessGuard.blockedSeconds(id)
            if (wait > 0) {
                send(Frame.Text("blocked $wait"))
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "blocked"))
                return@webSocket
            }

            // the login message has to arrive within 5 seconds
            val first = withTimeoutOrNull(5000) { incoming.receiveCatching().getOrNull() }
            val given = (first as? Frame.Text)?.readText()?.takeIf { it.startsWith("auth ") }?.substring(5)
            if (!auth.accessGuard.tokensEqual(given, auth.expectedToken())) {
                val nowBlocked = auth.accessGuard.recordFailure(id)
                logger.log("Rejected a live connection from $id: wrong token" + if (nowBlocked) " - blocked for 5 minutes" else "")
                send(Frame.Text("denied"))
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "denied"))
                return@webSocket
            }
            if (auth.accessGuard.recordSuccess(id)) {
                logger.log("Device connected: $id" + if (auth.isViaTunnel(call)) " (via the internet)" else "")
            }
            send(Frame.Text("ok"))

            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                when (val command = parseSocketCommand(frame.readText())) {
                    is SocketCommand.Move -> pcInput.moveMouseBy(command.dx, command.dy)
                    is SocketCommand.Click -> pcInput.click(if (command.right) MouseButton.RIGHT else MouseButton.LEFT)
                    is SocketCommand.Scroll -> pcInput.scroll(command.amount)
                    is SocketCommand.Ping -> send(Frame.Text("q ${command.stamp}"))
                    is SocketCommand.Point -> pcInput.moveMouseToFraction(command.x, command.y)
                    is SocketCommand.DoubleClick -> pcInput.doubleClick()
                    is SocketCommand.Button -> {
                        pcInput.setLeftButtonDown(command.down)
                        buttonHeld = command.down
                    }
                    is SocketCommand.Ack -> inFlight.updateAndGet { maxOf(0, it - 1) }
                    // direct typing from the other device's keyboard (same powers as /keyboard/live and /keyboard/key)
                    is SocketCommand.TypeText -> pcInput.liveType(0, command.text)
                    is SocketCommand.KeyPress -> {
                        if (pcInput.isValidKeyCombo(command.combo)) {
                            withContext(Dispatchers.IO) { pcInput.pressKeyCombo(command.combo) }
                        }
                    }
                    is SocketCommand.View -> {
                        if (!auth.accessGuard.allowScreenView.value) {
                            send(Frame.Text("viewdenied"))
                        } else {
                            streamJob?.cancel()
                            inFlight.set(0)
                            if (!viewing) {
                                viewing = true
                                screenStreamState.started()
                                logger.log("Screen viewing started: $id" + if (auth.isViaTunnel(call)) " (via the internet)" else "")
                            }
                            streamJob = launch(Dispatchers.IO) {
                                streamScreen(session, command, inFlight, auth, screenCapture)
                            }
                        }
                    }
                    is SocketCommand.ViewOff -> {
                        streamJob?.cancel()
                        streamJob = null
                        if (viewing) {
                            viewing = false
                            screenStreamState.stopped()
                            logger.log("Screen viewing stopped")
                        }
                    }
                    null -> { }   // unknown message: ignored
                }
            }
        } finally {
            streamJob?.cancel()
            if (viewing) {
                screenStreamState.stopped()
                logger.log("Screen viewing stopped")
            }
            if (buttonHeld) pcInput.setLeftButtonDown(false)   // never leave the mouse button stuck down
            liveConnections.decrementAndGet()
        }
    }
}

// Sends the screen to one phone as a series of small JPEG pictures, as many as the phone can take.
// The next picture is only sent after the phone has confirmed the earlier ones (the "a" message in
// SocketProtocol.kt). On a slow connection pictures are therefore skipped instead of piling up and arriving late.
private suspend fun streamScreen(
    session: WebSocketSession,
    settings: SocketCommand.View,
    inFlight: AtomicInteger,
    auth: AuthSupport,
    screenCapture: ScreenCapturePort
) {
    val view = viewRect(settings.zoomPercent, settings.cx, settings.cy)
    var lastFingerprint = 0
    var lastSent = 0L
    while (currentCoroutineContext().isActive) {
        if (!auth.accessGuard.allowScreenView.value) {        // switched off in the window while watching
            session.send(Frame.Text("viewdenied"))
            return
        }
        val now = System.currentTimeMillis()
        if (inFlight.get() >= 2 || now - lastSent < 60) {     // at most about 15 pictures a second
            delay(5)
            continue
        }
        val frame = try {
            screenCapture.captureFrame(
                view = view,
                targetWidth = settings.width,
                quality = settings.quality,
                lastFingerprint = lastFingerprint,
                forceSend = now - lastSent >= 2000
            )
        } catch (e: Exception) {
            session.send(Frame.Text("screenerror ${e.message ?: "cannot capture the screen"}"))
            delay(2000)
            continue
        }
        if (frame == null) {                                  // nothing changed: send nothing, look again soon
            delay(120)
            continue
        }
        lastFingerprint = frame.fingerprint
        lastSent = System.currentTimeMillis()
        inFlight.incrementAndGet()
        session.send(Frame.Binary(true, frame.jpegBytes))
    }
}
