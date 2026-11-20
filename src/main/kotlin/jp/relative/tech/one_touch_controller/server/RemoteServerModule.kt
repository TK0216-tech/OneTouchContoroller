package jp.relative.tech.one_touch_controller.server

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import jp.relative.tech.one_touch_controller.application.CommandRegistry
import jp.relative.tech.one_touch_controller.application.RoutineService
import jp.relative.tech.one_touch_controller.application.ScreenStreamState
import jp.relative.tech.one_touch_controller.domain.config.AppLogger
import jp.relative.tech.one_touch_controller.domain.ports.GuardEnginePort
import jp.relative.tech.one_touch_controller.domain.ports.PcInputPort
import jp.relative.tech.one_touch_controller.domain.ports.ScreenCapturePort
import jp.relative.tech.one_touch_controller.server.routes.commandRoutes
import jp.relative.tech.one_touch_controller.server.routes.guardRoutes
import jp.relative.tech.one_touch_controller.server.routes.inputRoutes
import jp.relative.tech.one_touch_controller.server.routes.routineRoutes
import jp.relative.tech.one_touch_controller.server.routes.webAppRoutes
import jp.relative.tech.one_touch_controller.server.routes.webSocketRoutes

class RemoteServerDeps(
    val auth: AuthSupport,
    val commandRegistry: CommandRegistry,
    val routineService: RoutineService,
    val guardEngine: GuardEnginePort,
    val pcInput: PcInputPort,
    val screenCapture: ScreenCapturePort,
    val screenStreamState: ScreenStreamState,
    val logger: AppLogger,
    val webAppHtmlProvider: () -> String = { loadWebAppHtml() }
)

fun loadWebAppHtml(): String {
    val stream = RemoteServerDeps::class.java.getResourceAsStream("/web/app.html")
        ?: error("Missing bundled web app resource: /web/app.html")
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}

fun Application.remoteModule(deps: RemoteServerDeps) {
    val cachedWebAppHtml = deps.webAppHtmlProvider()
    install(WebSockets) {
        pingPeriodMillis = 15_000     // keeps the connection alive through Cloudflare / routers
        timeoutMillis = 30_000
        maxFrameSize = 4096           // our messages are tiny; anything bigger is refused
    }
    routing {
        webAppRoutes(cachedWebAppHtml)
        commandRoutes(deps.auth, deps.commandRegistry, deps.logger)
        webSocketRoutes(deps.auth, deps.pcInput, deps.screenCapture, deps.screenStreamState, deps.logger)
        guardRoutes(deps.auth, deps.guardEngine, deps.logger)
        routineRoutes(deps.auth, deps.routineService, deps.logger)
        inputRoutes(deps.auth, deps.pcInput, deps.logger)
    }
}
