package jp.relative.tech.one_touch_controller.server

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import jp.relative.tech.one_touch_controller.application.AppPaths
import jp.relative.tech.one_touch_controller.application.CommandRegistry
import jp.relative.tech.one_touch_controller.application.LogStore
import jp.relative.tech.one_touch_controller.application.RoutineService
import jp.relative.tech.one_touch_controller.application.ScreenStreamState
import jp.relative.tech.one_touch_controller.application.TokenService
import jp.relative.tech.one_touch_controller.domain.ports.EncodedScreenFrame
import jp.relative.tech.one_touch_controller.domain.ports.GuardEnginePort
import jp.relative.tech.one_touch_controller.domain.ports.GuardState
import jp.relative.tech.one_touch_controller.domain.ports.MouseButton
import jp.relative.tech.one_touch_controller.domain.ports.PcInputPort
import jp.relative.tech.one_touch_controller.domain.ports.RecordingPort
import jp.relative.tech.one_touch_controller.domain.ports.ScreenCapturePort
import jp.relative.tech.one_touch_controller.domain.ports.SystemCommandPort
import jp.relative.tech.one_touch_controller.domain.recording.RecEvent
import jp.relative.tech.one_touch_controller.domain.screen.ViewRect
import jp.relative.tech.one_touch_controller.domain.security.AccessGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerContractTests {
    private class TestFixture(tempHome: File) {
        val paths = AppPaths(tempHome)
        val logger = LogStore()
        val accessGuard = AccessGuard()
        val tokenService = TokenService(paths.tokenFile, accessGuard, logger).apply {
            apply("test-token-1234567890", tunnelActive = false)
        }
        val screenStreamState = ScreenStreamState()

        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        @Volatile var guardTurnOnError: String? = null
        @Volatile var guardStatus = "off"
        @Volatile var buttonHeldDown = false

        val pcInput = object : PcInputPort {
            override fun moveMouseBy(dx: Int, dy: Int) { events.add("move:$dx,$dy") }
            override fun moveMouseToFraction(x: Int, y: Int) { events.add("point:$x,$y") }
            override fun click(button: MouseButton) { events.add("click:$button") }
            override fun doubleClick() { events.add("doubleClick") }
            override fun setLeftButtonDown(down: Boolean) { buttonHeldDown = down; events.add("button:$down") }
            override fun scroll(amount: Int) { events.add("scroll:$amount") }
            override fun isValidKeyCombo(combo: String): Boolean = combo in setOf("enter", "ctrl+c")
            override fun pressKeyCombo(combo: String) { events.add("key:$combo") }
            override suspend fun typeText(text: String) { events.add("type:$text") }
            override suspend fun liveType(backspaces: Int, text: String) { events.add("live:$backspaces:$text") }
            override fun takeScreenshot(): String = "Screenshot saved: shot.png (in Pictures)"
            override fun copyToClipboard(text: String) {}
        }

        val systemCommands = object : SystemCommandPort {
            override val valorantArgs: List<String> = emptyList()
            override fun startProcess(vararg cmd: String) { events.add("proc:${cmd.joinToString(" ")}") }
            override fun launchProgram(program: String, args: List<String>) {}
            override fun openTarget(target: String) {}
            override fun closeProcess(exe: String) {}
            override fun setPowerPlan(plan: String) {}
            override fun sendMediaKey(code: Int) { events.add("media:$code") }
            override fun lockWorkstation() { events.add("lock") }
            override fun shutdownIn(seconds: Int) { events.add("shutdown:$seconds") }
            override fun restartIn(seconds: Int) { events.add("restart:$seconds") }
            override fun cancelShutdown() { events.add("cancelShutdown") }
            override fun openInTextEditor(path: String) {}
            override fun findRiotClient(): String? = null
        }

        val guardEngine = object : GuardEnginePort {
            override val state: StateFlow<GuardState> = MutableStateFlow(GuardState(active = false, hasPassword = true))
            override fun load() {}
            override fun setPassword(text: String): String? = null
            override fun turnOn(who: String) {
                guardTurnOnError?.let { throw IllegalStateException(it) }
                guardStatus = "on 0"
                events.add("guardOn:$who")
            }
            override fun turnOff(who: String) {
                guardStatus = "off"
                events.add("guardOff:$who")
            }
            override fun showMessage(text: String) { events.add("msg:$text") }
            override fun statusText(): String = guardStatus
        }

        val recorder = object : RecordingPort {
            override val isRecording: Boolean = false
            override val isPlaying: Boolean = false
            override fun startRecording(progress: (Int) -> Unit, stopped: (List<RecEvent>) -> Unit) {}
            override fun stopRecording(fromButton: Boolean) {}
            override fun save(id: String, events: List<RecEvent>) {}
            override fun load(fileName: String): List<RecEvent> = emptyList()
            override fun delete(fileName: String) {}
            override fun listRecordingIds(): Set<String> = emptySet()
            override fun play(events: List<RecEvent>): Boolean = true
        }

        val screenCapture = object : ScreenCapturePort {
            override fun captureFrame(
                view: ViewRect,
                targetWidth: Int,
                quality: Int,
                lastFingerprint: Int,
                forceSend: Boolean
            ): EncodedScreenFrame = EncodedScreenFrame(fingerprint = 999, jpegBytes = byteArrayOf(1, 2, 3))
        }

        val commandRegistry = CommandRegistry.default(systemCommands, pcInput, guardEngine)
        val routineService = RoutineService(paths.routinesFile, commandRegistry, pcInput, systemCommands, recorder, logger).apply {
            paths.routinesFile.parentFile?.mkdirs()
            paths.routinesFile.writeText(
                """
                [safe] Safe Routine
                command mute

                [danger] Danger Routine
                command shutdown_60
                """.trimIndent()
            )
            reload()
        }

        val deps = RemoteServerDeps(
            auth = AuthSupport(accessGuard, tokenService, logger),
            commandRegistry = commandRegistry,
            routineService = routineService,
            guardEngine = guardEngine,
            pcInput = pcInput,
            screenCapture = screenCapture,
            screenStreamState = screenStreamState,
            logger = logger
        )
    }

    @Test
    fun webAppRoutesRespectBrowserDetectionAndServeBundledHtml(@TempDir tempHome: File) = testApplication {
        val fixture = TestFixture(tempHome)
        application { remoteModule(fixture.deps) }

        val nonBrowser = client.get("/") {
            header(HttpHeaders.Accept, "text/html")
            header(HttpHeaders.UserAgent, "OneTouchAndroid/1.0")
        }
        assertEquals(HttpStatusCode.OK, nonBrowser.status)
        assertEquals("PC Remote server is running", nonBrowser.bodyAsText())

        val browser = client.get("/") {
            header(HttpHeaders.Accept, "text/html")
            header(HttpHeaders.UserAgent, "Mozilla/5.0")
        }
        assertEquals(HttpStatusCode.OK, browser.status)
        assertEquals("no-cache", browser.headers["Cache-Control"])
        assertTrue(browser.bodyAsText().startsWith("<!DOCTYPE html>"))

        val appPage = client.get("/app")
        assertEquals(HttpStatusCode.OK, appPage.status)
        assertTrue(appPage.bodyAsText().contains("One Touch Controller"))
    }

    @Test
    fun authCommandsRoutinesGuardAndInputEndpointsMatchContract(@TempDir tempHome: File) = testApplication {
        val fixture = TestFixture(tempHome)
        application { remoteModule(fixture.deps) }

        // Unauthorized without token
        assertEquals(HttpStatusCode.Unauthorized, client.get("/guard").status)

        // Command success + unknown 404
        val okCmd = client.post("/run") {
            header("X-Auth-Token", "test-token-1234567890")
            setBody("""{"command":"mute"}""")
        }
        assertEquals(HttpStatusCode.OK, okCmd.status)
        assertEquals("Mute toggled", okCmd.bodyAsText())

        val unknownCmd = client.post("/run") {
            header("X-Auth-Token", "test-token-1234567890")
            setBody("""{"command":"nope"}""")
        }
        assertEquals(HttpStatusCode.NotFound, unknownCmd.status)

        // Dangerous command blocked via tunnel by default, allowed once enabled
        val blocked = client.post("/run") {
            header("X-Auth-Token", "test-token-1234567890")
            header("CF-Connecting-IP", "203.0.113.10")
            setBody("""{"command":"shutdown_60"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, blocked.status)

        fixture.accessGuard.setAllowDangerousOverInternet(true)
        val allowed = client.post("/run") {
            header("X-Auth-Token", "test-token-1234567890")
            header("CF-Connecting-IP", "203.0.113.10")
            setBody("""{"command":"shutdown_60"}""")
        }
        assertEquals(HttpStatusCode.OK, allowed.status)
        assertTrue(fixture.events.contains("guardOff:shutdown from the phone"))
        assertTrue(fixture.events.contains("shutdown:60"))

        // Routines listing includes tab-separated dangerous marker
        val routinesList = client.get("/routines") {
            header("X-Auth-Token", "test-token-1234567890")
        }
        assertEquals("safe\tSafe Routine\t\ndanger\tDanger Routine\t!", routinesList.bodyAsText())

        // Guard & message endpoints
        val guardOn = client.post("/guard") {
            header("X-Auth-Token", "test-token-1234567890")
            setBody("""{"on":"true"}""")
        }
        assertEquals(HttpStatusCode.OK, guardOn.status)
        assertEquals("Guard mode on", guardOn.bodyAsText())

        val guardStatus = client.get("/guard") { header("X-Auth-Token", "test-token-1234567890") }
        assertEquals("on 0", guardStatus.bodyAsText())

        val emptyMsg = client.post("/message") {
            header("X-Auth-Token", "test-token-1234567890")
            setBody("   ")
        }
        assertEquals(HttpStatusCode.BadRequest, emptyMsg.status)

        val validMsg = client.post("/message") {
            header("X-Auth-Token", "test-token-1234567890")
            setBody("Hello PC")
        }
        assertEquals(HttpStatusCode.OK, validMsg.status)
        assertTrue(fixture.events.contains("msg:Hello PC"))

        // Mouse & keyboard endpoints
        assertEquals(
            HttpStatusCode.OK,
            client.post("/mouse/click") {
                header("X-Auth-Token", "test-token-1234567890")
                setBody("""{"button":"double"}""")
            }.status
        )
        assertTrue(fixture.events.contains("doubleClick"))

        assertEquals(
            HttpStatusCode.NotFound,
            client.post("/keyboard/key") {
                header("X-Auth-Token", "test-token-1234567890")
                setBody("""{"key":"bad"}""")
            }.status
        )
        assertEquals(
            HttpStatusCode.OK,
            client.post("/keyboard/key") {
                header("X-Auth-Token", "test-token-1234567890")
                setBody("""{"key":"ctrl+c"}""")
            }.status
        )
        assertTrue(fixture.events.contains("key:ctrl+c"))
    }

    @Test
    fun webSocketHandlesAuthInputScreenViewAndCleanup(@TempDir tempHome: File) = testApplication {
        val fixture = TestFixture(tempHome)
        application { remoteModule(fixture.deps) }

        val wsClient = createClient { install(ClientWebSockets) }

        // Denied on wrong token
        wsClient.webSocket("/ws") {
            send(Frame.Text("auth wrong"))
            val reply = (incoming.receive() as Frame.Text).readText()
            assertEquals("denied", reply)
        }

        // Valid session: ping, input, hold mouse button (auto-released on disconnect), screen view binary frame
        wsClient.webSocket("/ws") {
            send(Frame.Text("auth test-token-1234567890"))
            assertEquals("ok", (incoming.receive() as Frame.Text).readText())

            send(Frame.Text("p 777"))
            assertEquals("q 777", (incoming.receive() as Frame.Text).readText())

            send(Frame.Text("m 12 -5"))
            send(Frame.Text("b down"))

            fixture.accessGuard.setAllowScreenView(false)
            send(Frame.Text("v 640 60 100 500 500"))
            assertEquals("viewdenied", (incoming.receive() as Frame.Text).readText())

            fixture.accessGuard.setAllowScreenView(true)
            send(Frame.Text("v 640 60 100 500 500"))
            val binaryFrame = incoming.receive() as Frame.Binary
            assertContentEquals(byteArrayOf(1, 2, 3), binaryFrame.readBytes())
            send(Frame.Text("v off"))
        }

        withTimeout(2_000) {
            while (fixture.buttonHeldDown || fixture.screenStreamState.viewers.value != 0) {
                delay(10)
            }
        }
        assertEquals(false, fixture.buttonHeldDown)
        assertEquals(0, fixture.screenStreamState.viewers.value)
        assertTrue(fixture.events.contains("move:12,-5"))
    }

    @Test
    fun rateLimitRoutineRunGuardConflictAndKeyboardTypingMatchContract(@TempDir tempHome: File) = testApplication {
        val fixture = TestFixture(tempHome)
        application { remoteModule(fixture.deps) }

        // 5 failed tokens -> 429 Too Many Requests on next attempt
        repeat(5) {
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/guard") {
                    header("X-Auth-Token", "wrong")
                    header("CF-Connecting-IP", "198.51.100.7")
                }.status
            )
        }
        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.get("/guard") {
                header("X-Auth-Token", "test-token-1234567890")
                header("CF-Connecting-IP", "198.51.100.7")
            }.status
        )

        // Dangerous routine blocked over internet tunnel
        val dangerRoutineRes = client.post("/routine/run") {
            header("X-Auth-Token", "test-token-1234567890")
            header("CF-Connecting-IP", "203.0.113.20")
            setBody("""{"id":"danger"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, dangerRoutineRes.status)

        // Safe routine runs and reports status
        val safeRoutineRes = client.post("/routine/run") {
            header("X-Auth-Token", "test-token-1234567890")
            setBody("""{"id":"safe"}""")
        }
        assertEquals(HttpStatusCode.OK, safeRoutineRes.status)
        assertEquals("Started: Safe Routine", safeRoutineRes.bodyAsText())

        // Guard mode failure returns 409 Conflict
        fixture.guardTurnOnError = "Set a guard password in the PC window first"
        val guardConflict = client.post("/guard") {
            header("X-Auth-Token", "test-token-1234567890")
            setBody("""{"on":"true"}""")
        }
        assertEquals(HttpStatusCode.Conflict, guardConflict.status)

        // Keyboard type & live endpoints validation
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/keyboard/type") {
                header("X-Auth-Token", "test-token-1234567890")
                setBody("")
            }.status
        )
        assertEquals(
            HttpStatusCode.OK,
            client.post("/keyboard/type") {
                header("X-Auth-Token", "test-token-1234567890")
                setBody("こんにちは")
            }.status
        )
        assertTrue(fixture.events.contains("type:こんにちは"))

        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/keyboard/live") {
                header("X-Auth-Token", "test-token-1234567890")
                setBody("not-a-number\nabc")
            }.status
        )
        assertEquals(
            HttpStatusCode.OK,
            client.post("/keyboard/live") {
                header("X-Auth-Token", "test-token-1234567890")
                setBody("2\nabc")
            }.status
        )
        assertTrue(fixture.events.contains("live:2:abc"))
    }
}
