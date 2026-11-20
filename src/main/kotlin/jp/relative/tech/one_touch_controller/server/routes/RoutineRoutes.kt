package jp.relative.tech.one_touch_controller.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import jp.relative.tech.one_touch_controller.application.RoutineService
import jp.relative.tech.one_touch_controller.domain.config.AppLogger
import jp.relative.tech.one_touch_controller.server.AuthSupport
import jp.relative.tech.one_touch_controller.server.jsonField

internal fun Route.routineRoutes(
    auth: AuthSupport,
    routineService: RoutineService,
    logger: AppLogger
) {
    // ---- Routines (automation): the phone only sends the NAME of a routine ----
    get("/routines") {
        if (!auth.checkToken(call)) return@get
        // one line per routine:  id <TAB> name <TAB> "!" if it contains shutdown/restart/script commands
        val list = routineService.state.value.routines.joinToString("\n") { r ->
            r.id + "\t" + r.name + "\t" + (if (routineService.isDangerous(r)) "!" else "")
        }
        call.respondText(list)
    }

    // Is the routine still running? "running", "finished <failed steps>" or "unknown"
    get("/routine/status") {
        if (!auth.checkToken(call)) return@get
        val id = call.request.queryParameters["id"] ?: ""
        call.respondText(routineService.statusText(id))
    }

    post("/routine/run") {
        if (!auth.checkToken(call)) return@post
        val routine = routineService.find(jsonField(call.receiveText(), "id"))
        if (routine == null) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        if (routineService.isDangerous(routine) && auth.isViaTunnel(call) && !auth.accessGuard.allowDangerousOverInternet.value) {
            logger.log("Blocked over the internet: routine ${routine.id}")
            call.respondText(
                "Blocked: this routine has shutdown/restart/script steps, which are disabled over the internet.",
                status = HttpStatusCode.Forbidden
            )
            return@post
        }
        // runs in the background, the phone does not have to wait
        val from = if (auth.isViaTunnel(call)) "phone, via the internet" else "phone"
        call.respondText(routineService.start(routine, from))
    }
}
