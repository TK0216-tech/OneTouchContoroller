package jp.relative.tech.one_touch_controller.application

import jp.relative.tech.one_touch_controller.domain.config.AppLanguage
import jp.relative.tech.one_touch_controller.domain.ports.GuardEnginePort
import jp.relative.tech.one_touch_controller.domain.ports.GuardState
import jp.relative.tech.one_touch_controller.domain.ports.MouseButton
import jp.relative.tech.one_touch_controller.domain.ports.PcInputPort
import jp.relative.tech.one_touch_controller.domain.ports.RecordingPort
import jp.relative.tech.one_touch_controller.domain.ports.SystemCommandPort
import jp.relative.tech.one_touch_controller.domain.recording.RecEvent
import jp.relative.tech.one_touch_controller.domain.security.AccessGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplicationTests {
    @Test
    fun appPathsMatchLegacyLocations(@TempDir tempHome: File) {
        val paths = AppPaths(tempHome)
        assertEquals(File(tempHome, ".onetouch_token"), paths.tokenFile)
        assertEquals(File(tempHome, ".onetouch/settings.properties"), paths.settingsFile)
        assertEquals(File(tempHome, ".onetouch/routines.txt"), paths.routinesFile)
        assertEquals(File(tempHome, ".onetouch/recordings"), paths.recordingsDir)
        assertEquals(File(tempHome, ".onetouch/guard.properties"), paths.guardPropertiesFile)
        assertEquals(File(tempHome, ".onetouch/cloudflared.exe"), paths.cloudflaredFile)
        assertEquals(File(tempHome, "Pictures"), paths.screenshotsDir)
    }

    @Test
    fun settingsServicePersistsAndLoads(@TempDir tempHome: File) {
        val paths = AppPaths(tempHome)
        val s1 = SettingsService(paths.settingsFile, defaultLocaleProvider = { Locale.JAPANESE })
        s1.load()
        assertEquals(AppLanguage.JA, s1.current.language)
        assertTrue(s1.current.dark)

        s1.setDark(false)
        s1.setAccentIndex(3)
        s1.setLanguage(AppLanguage.ZH)

        val s2 = SettingsService(paths.settingsFile, defaultLocaleProvider = { Locale.ENGLISH })
        s2.load()
        assertFalse(s2.current.dark)
        assertEquals(3, s2.current.accentIndex)
        assertEquals(AppLanguage.ZH, s2.current.language)
    }

    @Test
    fun tokenServiceValidatesAndPersists(@TempDir tempHome: File) {
        val paths = AppPaths(tempHome)
        val logStore = LogStore()
        val service = TokenService(paths.tokenFile, AccessGuard(), logStore)
        service.load()

        assertEquals(TokenApplyResult.Empty, service.apply("   ", tunnelActive = false))
        assertTrue(service.apply("short", tunnelActive = true) is TokenApplyResult.TooShortForInternet)
        assertEquals(TokenApplyResult.Success, service.apply("a-very-long-secure-token-123", tunnelActive = true))
        assertEquals("a-very-long-secure-token-123", paths.tokenFile.readText())
    }

    @Test
    fun commandRegistryCentralizesDangerousFlags() {
        val registry = CommandRegistry.default(FakeSystemCommands(), FakePcInput(), FakeGuardEngine())
        assertEquals(18, registry.knownIds.size)
        assertEquals(setOf("shutdown_60", "restart_60", "run_script_1"), registry.dangerousIds)
        assertNotNull(registry.find("mute"))
        assertNull(registry.find("does_not_exist"))
    }

    @Test
    fun routineServiceSavesRecordingAndCleansOrphanedRecordingOnDelete(@TempDir tempHome: File) {
        val paths = AppPaths(tempHome)
        val logStore = LogStore()
        val pcInput = FakePcInput()
        val system = FakeSystemCommands()
        val guard = FakeGuardEngine()
        val recordings = FakeRecordingPort()
        val registry = CommandRegistry.default(system, pcInput, guard)
        val service = RoutineService(paths.routinesFile, registry, pcInput, system, recordings, logStore)

        service.reload()
        assertEquals(listOf("valorant", "normal"), service.state.value.routines.map { it.id })

        service.saveRecordingAsRoutine("My Task", listOf(RecEvent(100, "m", 10, 20)))
        val saved = service.find("my-task")
        assertNotNull(saved)
        assertTrue("my-task.rec" in recordings.savedFiles)

        val err = service.delete(saved)
        assertNull(err)
        assertNull(service.find("my-task"))
        assertFalse("my-task.rec" in recordings.savedFiles)
    }

    @Test
    fun i18nTranslatesAndSubstitutesPlaceholders() {
        assertEquals("実行中（ポート 8080）", I18n.translate(AppLanguage.JA, "Running (port %1)", 8080))
        assertEquals("运行中（端口 8080）", I18n.translate(AppLanguage.ZH, "Running (port %1)", 8080))
        assertEquals("Running (port 8080)", I18n.translate(AppLanguage.EN, "Running (port %1)", 8080))
        assertEquals("Untranslated 42", I18n.translate(AppLanguage.JA, "Untranslated %1", 42))
    }

    @Test
    fun logStoreCapsEntriesAndClears() {
        val store = LogStore(maxEntries = 3)
        repeat(5) { store.log("msg $it") }
        assertEquals(3, store.lines.value.size)
        assertTrue(store.lines.value.first().endsWith("msg 2"))
        assertTrue(store.lines.value.last().endsWith("msg 4"))
        store.clear()
        assertTrue(store.lines.value.isEmpty())
    }
}

private class FakePcInput : PcInputPort {
    override fun moveMouseBy(dx: Int, dy: Int) {}
    override fun moveMouseToFraction(x: Int, y: Int) {}
    override fun click(button: MouseButton) {}
    override fun doubleClick() {}
    override fun setLeftButtonDown(down: Boolean) {}
    override fun scroll(amount: Int) {}
    override fun isValidKeyCombo(combo: String): Boolean = combo != "invalid"
    override fun pressKeyCombo(combo: String) {}
    override suspend fun typeText(text: String) {}
    override suspend fun liveType(backspaces: Int, text: String) {}
    override fun takeScreenshot(): String = "Screenshot saved: test.png (in Pictures)"
    override fun copyToClipboard(text: String) {}
}

private class FakeSystemCommands : SystemCommandPort {
    override val valorantArgs: List<String> = listOf("--launch-product=valorant", "--launch-patchline=live")
    override fun startProcess(vararg cmd: String) {}
    override fun launchProgram(program: String, args: List<String>) {}
    override fun openTarget(target: String) {}
    override fun closeProcess(exe: String) {}
    override fun setPowerPlan(plan: String) {}
    override fun sendMediaKey(code: Int) {}
    override fun lockWorkstation() {}
    override fun shutdownIn(seconds: Int) {}
    override fun restartIn(seconds: Int) {}
    override fun cancelShutdown() {}
    override fun openInTextEditor(path: String) {}
    override fun findRiotClient(): String? = null
}

private class FakeGuardEngine : GuardEnginePort {
    override val state: StateFlow<GuardState> = MutableStateFlow(GuardState())
    override fun load() {}
    override fun setPassword(text: String): String? = null
    override fun turnOn(who: String) {}
    override fun turnOff(who: String) {}
    override fun showMessage(text: String) {}
    override fun statusText(): String = "off"
}

private class FakeRecordingPort : RecordingPort {
    val savedFiles = mutableMapOf<String, List<RecEvent>>()
    override val isRecording: Boolean = false
    override val isPlaying: Boolean = false
    override fun startRecording(progress: (Int) -> Unit, stopped: (List<RecEvent>) -> Unit) {}
    override fun stopRecording(fromButton: Boolean) {}
    override fun save(id: String, events: List<RecEvent>) { savedFiles["$id.rec"] = events }
    override fun load(fileName: String): List<RecEvent> = savedFiles[fileName] ?: emptyList()
    override fun delete(fileName: String) { savedFiles.remove(fileName) }
    override fun listRecordingIds(): Set<String> = savedFiles.keys.map { it.removeSuffix(".rec") }.toSet()
    override fun play(events: List<RecEvent>): Boolean = true
}
