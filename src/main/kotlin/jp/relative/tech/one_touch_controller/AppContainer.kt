package jp.relative.tech.one_touch_controller

import jp.relative.tech.one_touch_controller.application.AppPaths
import jp.relative.tech.one_touch_controller.application.CommandRegistry
import jp.relative.tech.one_touch_controller.application.I18n
import jp.relative.tech.one_touch_controller.application.LogStore
import jp.relative.tech.one_touch_controller.application.RoutineService
import jp.relative.tech.one_touch_controller.application.ScreenStreamState
import jp.relative.tech.one_touch_controller.application.SettingsService
import jp.relative.tech.one_touch_controller.application.TokenService
import jp.relative.tech.one_touch_controller.domain.config.DEFAULT_DISCOVERY_PORT
import jp.relative.tech.one_touch_controller.domain.config.DEFAULT_SERVER_PORT
import jp.relative.tech.one_touch_controller.domain.ports.GuardEnginePort
import jp.relative.tech.one_touch_controller.domain.ports.PcInputPort
import jp.relative.tech.one_touch_controller.domain.ports.RecordingPort
import jp.relative.tech.one_touch_controller.domain.ports.ScreenCapturePort
import jp.relative.tech.one_touch_controller.domain.ports.SystemCommandPort
import jp.relative.tech.one_touch_controller.domain.ports.TunnelPort
import jp.relative.tech.one_touch_controller.domain.security.AccessGuard
import jp.relative.tech.one_touch_controller.platform.windows.guard.WindowsGuardEngine
import jp.relative.tech.one_touch_controller.platform.windows.input.JNativeInputRecorder
import jp.relative.tech.one_touch_controller.platform.windows.input.RobotPcInput
import jp.relative.tech.one_touch_controller.platform.windows.input.WindowsSystemCommands
import jp.relative.tech.one_touch_controller.platform.windows.screen.AwtScreenCapturer
import jp.relative.tech.one_touch_controller.platform.windows.tunnel.CloudflaredTunnel
import jp.relative.tech.one_touch_controller.server.AuthSupport
import jp.relative.tech.one_touch_controller.server.DiscoveryService
import jp.relative.tech.one_touch_controller.server.RemoteServerDeps
import jp.relative.tech.one_touch_controller.server.ServerController
import java.awt.Robot

/**
 * Composition root that wires all layers together using explicit constructor injection.
 */
class AppContainer(
    val paths: AppPaths = AppPaths(),
    serverPort: Int = DEFAULT_SERVER_PORT,
    discoveryPort: Int = DEFAULT_DISCOVERY_PORT
) {
    private val sharedRobot: Robot by lazy { Robot() }

    val logStore = LogStore()
    val settingsService = SettingsService(paths.settingsFile)
    val translator = I18n.dynamic { settingsService.current.language }
    val accessGuard = AccessGuard()
    val tokenService = TokenService(paths.tokenFile, accessGuard, logStore)
    val screenStreamState = ScreenStreamState()

    val pcInput: PcInputPort = RobotPcInput(paths.screenshotsDir) { sharedRobot }
    val systemCommands: SystemCommandPort = WindowsSystemCommands()
    val recorder: RecordingPort = JNativeInputRecorder(paths.recordingsDir) { sharedRobot }
    val screenCapture: ScreenCapturePort = AwtScreenCapturer { sharedRobot }
    val guardEngine: GuardEnginePort = WindowsGuardEngine(
        propertiesFile = paths.guardPropertiesFile,
        languageProvider = { settingsService.current.language },
        accentRgbProvider = { settingsService.current.accentRgb() },
        translator = translator,
        logger = logStore
    )
    val tunnel: TunnelPort = CloudflaredTunnel(
        ownCopy = paths.cloudflaredFile,
        serverPort = serverPort,
        logger = logStore
    )

    val commandRegistry = CommandRegistry.default(systemCommands, pcInput, guardEngine)
    val routineService = RoutineService(
        routinesFile = paths.routinesFile,
        commandRegistry = commandRegistry,
        pcInput = pcInput,
        system = systemCommands,
        recordingPort = recorder,
        logger = logStore
    )

    val authSupport = AuthSupport(accessGuard, tokenService, logStore)
    val serverDeps = RemoteServerDeps(
        auth = authSupport,
        commandRegistry = commandRegistry,
        routineService = routineService,
        guardEngine = guardEngine,
        pcInput = pcInput,
        screenCapture = screenCapture,
        screenStreamState = screenStreamState,
        logger = logStore
    )
    val discoveryService = DiscoveryService(discoveryPort, logStore)
    val serverController = ServerController(
        port = serverPort,
        deps = serverDeps,
        discoveryService = discoveryService,
        tunnel = tunnel,
        logger = logStore
    )

    fun initialize() {
        settingsService.load()     // theme, accent colour and language of the window
        tokenService.load()
        routineService.reload()
        guardEngine.load()         // Guard password; also repairs the power button if the program was closed while guarding
    }

    fun shutdown() {
        tunnel.stop()              // close the internet tunnel first
        serverController.stop()    // stop the server properly, then close
    }
}
