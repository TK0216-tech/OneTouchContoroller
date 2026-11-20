package jp.relative.tech.one_touch_controller.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import jp.relative.tech.one_touch_controller.domain.config.AppLogger
import jp.relative.tech.one_touch_controller.domain.ports.GuardEnginePort
import jp.relative.tech.one_touch_controller.server.AuthSupport
import jp.relative.tech.one_touch_controller.server.jsonField

internal fun Route.guardRoutes(
    auth: AuthSupport,
    guardEngine: GuardEnginePort,
    logger: AppLogger
) {
    // ---- Guard mode: block the PC's own mouse and keyboard while you use it from the phone ----
    get("/guard") {
        if (!auth.checkToken(call)) return@get
        call.respondText(guardEngine.statusText())          // "off", "off locked", "nopassword" or "on <wrong passwords>"
    }

    post("/guard") {
        if (!auth.checkToken(call)) return@post
        val on = jsonField(call.receiveText(), "on") == "true"
        val from = if (auth.isViaTunnel(call)) "from the phone, via the internet" else "from the phone"
        try {
            if (on) guardEngine.turnOn(from) else guardEngine.turnOff(from)
            call.respondText(if (on) "Guard mode on" else "Guard mode off")
        } catch (e: Exception) {
            logger.log("Guard mode could not start: ${e.message}")
            call.respondText(e.message ?: "Guard mode could not start", status = HttpStatusCode.Conflict)
        }
    }

    // ---- A message from the phone, shown on the PC (on the lock screen while Guard mode is on) ----
    post("/message") {
        if (!auth.checkToken(call)) return@post
        val text = call.receiveText().trim()
        if (text.isEmpty()) {
            call.respondText("The message is empty", status = HttpStatusCode.BadRequest)
            return@post
        }
        guardEngine.showMessage(text)
        call.respondText("Message shown on the PC")
    }
}
