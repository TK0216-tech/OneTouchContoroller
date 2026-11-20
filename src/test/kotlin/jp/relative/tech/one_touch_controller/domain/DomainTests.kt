package jp.relative.tech.one_touch_controller.domain

import jp.relative.tech.one_touch_controller.domain.protocol.SocketCommand
import jp.relative.tech.one_touch_controller.domain.protocol.parseSocketCommand
import jp.relative.tech.one_touch_controller.domain.recording.RecEvent
import jp.relative.tech.one_touch_controller.domain.recording.RecordingBuilder
import jp.relative.tech.one_touch_controller.domain.recording.ReplayTarget
import jp.relative.tech.one_touch_controller.domain.recording.parseRecording
import jp.relative.tech.one_touch_controller.domain.recording.recordingId
import jp.relative.tech.one_touch_controller.domain.recording.recordingToText
import jp.relative.tech.one_touch_controller.domain.recording.replayRecording
import jp.relative.tech.one_touch_controller.domain.routine.DEFAULT_ROUTINES
import jp.relative.tech.one_touch_controller.domain.routine.Routine
import jp.relative.tech.one_touch_controller.domain.routine.RoutineActions
import jp.relative.tech.one_touch_controller.domain.routine.Step
import jp.relative.tech.one_touch_controller.domain.routine.executeRoutine
import jp.relative.tech.one_touch_controller.domain.routine.parseRoutines
import jp.relative.tech.one_touch_controller.domain.routine.removeRoutineText
import jp.relative.tech.one_touch_controller.domain.routine.splitArgs
import jp.relative.tech.one_touch_controller.domain.screen.ViewRect
import jp.relative.tech.one_touch_controller.domain.screen.viewRect
import jp.relative.tech.one_touch_controller.domain.security.AccessGuard
import jp.relative.tech.one_touch_controller.domain.security.GuardAttempts
import jp.relative.tech.one_touch_controller.domain.security.GuardPassword
import jp.relative.tech.one_touch_controller.domain.security.guardKeyChar
import jp.relative.tech.one_touch_controller.domain.security.parsePowercfgValues
import jp.relative.tech.one_touch_controller.platform.windows.input.KeyMapping
import kotlinx.coroutines.test.runTest
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainTests {
    @Test
    fun socketProtocolParsesAllCommands() {
        assertEquals(SocketCommand.Move(10, -20), parseSocketCommand("m 10 -20"))
        assertEquals(SocketCommand.Move(1000, -1000), parseSocketCommand("m 5000 -5000"))
        assertEquals(SocketCommand.Click(right = false), parseSocketCommand("c left"))
        assertEquals(SocketCommand.Click(right = true), parseSocketCommand("c right"))
        assertEquals(SocketCommand.DoubleClick, parseSocketCommand("c double"))
        assertEquals(SocketCommand.Scroll(5), parseSocketCommand("s 5"))
        assertEquals(SocketCommand.Scroll(-50), parseSocketCommand("s -999"))
        assertEquals(SocketCommand.Ping("12345"), parseSocketCommand("p 12345"))
        assertEquals(SocketCommand.View(800, 60, 200, 500, 500), parseSocketCommand("v 800 60 200 500 500"))
        assertEquals(SocketCommand.ViewOff, parseSocketCommand("v off"))
        assertEquals(SocketCommand.Ack, parseSocketCommand("a"))
        assertEquals(SocketCommand.Point(250, 750), parseSocketCommand("u 250 750"))
        assertEquals(SocketCommand.Button(down = true), parseSocketCommand("b down"))
        assertEquals(SocketCommand.Button(down = false), parseSocketCommand("b up"))
        assertEquals(SocketCommand.TypeText("  hello\n "), parseSocketCommand("t   hello\n "))
        assertEquals(SocketCommand.KeyPress("ctrl+c"), parseSocketCommand("k CTRL+C"))
        assertNull(parseSocketCommand("unknown"))
        assertNull(parseSocketCommand("t "))
    }

    @Test
    fun routinesParsingRemovalAndArgsWork() {
        assertEquals(
            listOf("C:\\Program Files\\App\\app.exe", "--flag", "value"),
            splitArgs("\"C:\\Program Files\\App\\app.exe\" --flag value")
        )
        val parsed = parseRoutines(DEFAULT_ROUTINES, setOf("mute", "shutdown_60")) { true }
        assertTrue(parsed.problems.isEmpty())
        assertEquals(listOf("valorant", "normal"), parsed.routines.map { it.id })

        val removed = removeRoutineText(DEFAULT_ROUTINES, "valorant")
        assertNotNull(removed)
        val parsedAfter = parseRoutines(removed, setOf("mute", "shutdown_60")) { true }
        assertEquals(listOf("normal"), parsedAfter.routines.map { it.id })
    }

    @Test
    fun routineExecutionCountsFailuresAndContinues() = runTest {
        val logs = mutableListOf<String>()
        val executed = mutableListOf<String>()
        val routine = Routine("r1", "Test", listOf(Step.Command("a"), Step.Command("b")))
        val actions = object : RoutineActions {
            override fun launch(program: String, args: List<String>) {}
            override fun open(target: String) {}
            override fun key(combo: String) {}
            override fun close(exe: String) {}
            override fun power(plan: String) {}
            override fun command(id: String) {
                executed.add(id)
                if (id == "a") throw RuntimeException("boom")
            }
            override fun replay(file: String) {}
            override fun valorant() {}
            override fun log(message: String) { logs.add(message) }
        }
        val failed = executeRoutine(routine, actions)
        assertEquals(1, failed)
        assertEquals(listOf("a", "b"), executed)
        assertTrue(logs.any { it.contains("This step failed: boom") })
        assertTrue(logs.last().contains("finished"))
    }

    @Test
    fun recordingRoundTripDropLastClickAndSafeReplay() {
        val builder = RecordingBuilder(startMs = 1000L)
        builder.add("d", 1, 100, 200, 1050L)
        builder.add("kd", 65, 0, 0, 1100L)
        val events = builder.finish()
        assertEquals(events, parseRecording(recordingToText(events)))
        assertEquals("open-my-mail-2", recordingId("Open my mail!", setOf("open-my-mail")))

        val releasedButtons = mutableListOf<Int>()
        val releasedKeys = mutableListOf<Int>()
        val target = object : ReplayTarget {
            override fun mouseMove(x: Int, y: Int) {}
            override fun mousePress(button: Int) {}
            override fun mouseRelease(button: Int) { releasedButtons.add(button) }
            override fun wheel(notches: Int) {}
            override fun keyPress(keyCode: Int) {}
            override fun keyRelease(keyCode: Int) { releasedKeys.add(keyCode) }
        }
        var calls = 0
        val finished = replayRecording(
            events = listOf(RecEvent(0, "d", 1, 10, 10), RecEvent(0, "kd", 65), RecEvent(100, "m", 20, 20)),
            target = target,
            mapKey = { it },
            shouldStop = { ++calls > 3 },
            sleep = {}
        )
        assertFalse(finished)
        assertEquals(listOf(1), releasedButtons)
        assertEquals(listOf(65), releasedKeys)
    }

    @Test
    fun accessGuardRateLimitingAndTokenCheckWork() {
        var now = 1_000_000L
        val guard = AccessGuard(clock = { now })
        assertTrue(guard.tokensEqual("secret", "secret"))
        assertFalse(guard.tokensEqual("secret", "other"))
        assertFalse(guard.tokensEqual(null, "secret"))
        assertEquals(24, guard.generateToken().length)
        assertTrue(guard.isLoopback("127.0.0.1"))
        assertTrue(guard.isLoopback("::ffff:127.0.0.1"))
        assertFalse(guard.isLoopback("192.168.1.5"))

        repeat(4) { assertFalse(guard.recordFailure("1.2.3.4")) }
        assertTrue(guard.recordFailure("1.2.3.4"))
        assertEquals(300L, guard.blockedSeconds("1.2.3.4"))

        now += 300_001L
        assertEquals(0L, guard.blockedSeconds("1.2.3.4"))
    }

    @Test
    fun guardLogicViewRectAndKeyMappingWork() {
        assertTrue(GuardPassword.isValid("Ab12"))
        assertFalse(GuardPassword.isValid("ab1"))
        val salt = GuardPassword.newSalt()
        val hash = GuardPassword.hash("Ab12", salt)
        assertTrue(GuardPassword.matches("ab12", salt, hash))
        assertFalse(GuardPassword.matches("ab13", salt, hash))

        assertEquals('A', guardKeyChar(0x41))
        assertEquals('5', guardKeyChar(0x65))
        assertNull(guardKeyChar(0x0D))

        val attempts = GuardAttempts(waitMs = 60_000)
        repeat(4) { assertFalse(attempts.recordWrong(1000L)) }
        assertTrue(attempts.recordWrong(1000L))
        assertEquals(60L, attempts.secondsLeft(1000L))

        assertEquals(
            0 to 1,
            parsePowercfgValues("Current AC Power Setting Index: 0x00000000\nCurrent DC Power Setting Index: 0x00000001")
        )

        assertEquals(ViewRect(0.25, 0.25, 0.5), viewRect(zoomPercent = 200, cx = 500, cy = 500))
        assertEquals(ViewRect(0.5, 0.5, 0.5), viewRect(zoomPercent = 200, cx = 1000, cy = 1000))

        assertTrue(KeyMapping.isValidKeyCombo("ctrl+shift+f12"))
        assertFalse(KeyMapping.isValidKeyCombo("ctrl+badkey"))
        assertEquals(KeyEvent.VK_ENTER, KeyMapping.windowsKeyToJava(13))
    }
}
