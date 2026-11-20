package jp.relative.tech.one_touch_controller.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import jp.relative.tech.one_touch_controller.domain.config.AppLogger
import jp.relative.tech.one_touch_controller.domain.ports.MouseButton
import jp.relative.tech.one_touch_controller.domain.ports.PcInputPort
import jp.relative.tech.one_touch_controller.server.AuthSupport
import jp.relative.tech.one_touch_controller.server.jsonField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun Route.inputRoutes(
    auth: AuthSupport,
    pcInput: PcInputPort,
    logger: AppLogger
) {
    // ---- Touchpad: move the mouse by (dx, dy) pixels ----
    post("/mouse/move") {
        if (!auth.checkToken(call)) return@post
        val body = call.receiveText()
        val dx = jsonField(body, "dx")?.toIntOrNull() ?: 0
        val dy = jsonField(body, "dy")?.toIntOrNull() ?: 0
        pcInput.moveMouseBy(dx, dy)
        call.respondText("OK")
    }

    // ---- Touchpad: click ("left", "right" or "double") ----
    post("/mouse/click") {
        if (!auth.checkToken(call)) return@post
        when (jsonField(call.receiveText(), "button")) {
            "right" -> pcInput.click(MouseButton.RIGHT)
            "double" -> pcInput.doubleClick()
            else -> pcInput.click(MouseButton.LEFT)
        }
        call.respondText("OK")
    }

    // ---- Touchpad: scroll (positive = down, negative = up) ----
    post("/mouse/scroll") {
        if (!auth.checkToken(call)) return@post
        val amount = jsonField(call.receiveText(), "amount")?.toIntOrNull() ?: 0
        pcInput.scroll(amount)
        call.respondText("OK")
    }

    // ---- Keyboard: type a piece of text (plain UTF-8 text in the request body) ----
    post("/keyboard/type") {
        if (!auth.checkToken(call)) return@post
        val text = call.receiveText()
        if (text.isEmpty() || text.length > 5000) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        try {
            pcInput.typeText(text)
        } catch (e: Exception) {
            logger.log("Typing failed: ${e.message}")
            call.respondText("Failed: ${e.message}", status = HttpStatusCode.InternalServerError)
            return@post
        }
        logger.log("Typed ${text.length} characters")   // the text itself is not logged (could be a password)
        call.respondText("Typed ${text.length} characters")
    }

    // ---- Keyboard: live typing. Body = "<number of backspaces>\n<text to type>" ----
    post("/keyboard/live") {
        if (!auth.checkToken(call)) return@post
        val body = call.receiveText()
        val nl = body.indexOf('\n')
        val backspaces = if (nl > 0) body.substring(0, nl).trim().toIntOrNull() else null
        val text = if (nl >= 0) body.substring(nl + 1) else ""
        if (backspaces == null || backspaces !in 0..500 || text.length > 500) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        try {
            pcInput.liveType(backspaces, text)   // not logged: it happens on every key press and could be a password
        } catch (e: Exception) {
            call.respondText("Failed: ${e.message}", status = HttpStatusCode.InternalServerError)
            return@post
        }
        call.respondText("OK")
    }

    // ---- Keyboard: press a key or shortcut, e.g. {"key":"enter"} or {"key":"ctrl+c"} ----
    post("/keyboard/key") {
        if (!auth.checkToken(call)) return@post
        val combo = jsonField(call.receiveText(), "key")?.lowercase()
        if (combo == null || !pcInput.isValidKeyCombo(combo)) {
            logger.log("Rejected unknown key: $combo")
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        try {
            withContext(Dispatchers.IO) { pcInput.pressKeyCombo(combo) }
        } catch (e: Exception) {
            logger.log("Key failed: ${e.message}")
            call.respondText("Failed: ${e.message}", status = HttpStatusCode.InternalServerError)
            return@post
        }
        logger.log("Key: $combo")
        call.respondText("OK")
    }
}
