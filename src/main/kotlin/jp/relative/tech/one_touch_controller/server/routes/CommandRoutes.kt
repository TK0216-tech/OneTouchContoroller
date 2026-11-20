package jp.relative.tech.one_touch_controller.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import jp.relative.tech.one_touch_controller.application.CommandRegistry
import jp.relative.tech.one_touch_controller.domain.config.AppLogger
import jp.relative.tech.one_touch_controller.server.AuthSupport
import jp.relative.tech.one_touch_controller.server.jsonField

internal fun Route.commandRoutes(
    auth: AuthSupport,
    commandRegistry: CommandRegistry,
    logger: AppLogger
) {
    // ---- Run a whitelisted command ----
    post("/run") {
        if (!auth.checkToken(call)) return@post
        val name = jsonField(call.receiveText(), "command")
        val command = commandRegistry.find(name)
        if (command == null) {
            logger.log("Rejected unknown command: $name")
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        if (command.dangerousOverInternet && auth.isViaTunnel(call) && !auth.accessGuard.allowDangerousOverInternet.value) {
            logger.log("Blocked over the internet: $name")
            call.respondText(
                "Blocked: this command is switched off for internet access (see the PC window).",
                status = HttpStatusCode.Forbidden
            )
            return@post
        }
        logger.log("Command: $name")
        val message = try {
            command.execute()
        } catch (e: Exception) {
            logger.log("Command failed: ${e.message}")
            call.respondText("Failed: ${e.message}", status = HttpStatusCode.InternalServerError)
            return@post
        }
        call.respondText(message)
    }
}
