package jp.relative.tech.one_touch_controller.platform.windows.guard

import jp.relative.tech.one_touch_controller.domain.config.AppLanguage
import jp.relative.tech.one_touch_controller.domain.config.AppLogger
import jp.relative.tech.one_touch_controller.domain.config.StringTranslator
import jp.relative.tech.one_touch_controller.domain.ports.GuardEnginePort
import jp.relative.tech.one_touch_controller.domain.ports.GuardState
import jp.relative.tech.one_touch_controller.domain.security.GuardAttempts
import jp.relative.tech.one_touch_controller.domain.security.GuardPassword
import jp.relative.tech.one_touch_controller.domain.security.GuardPromptState
import jp.relative.tech.one_touch_controller.domain.security.guardKeyChar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Base64
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates Guard mode on Windows: password persistence, input blocking, lock overlay,
 * owner message popup, and temporary power-button disabling.
 */
class WindowsGuardEngine(
    private val propertiesFile: File,
    private val languageProvider: () -> AppLanguage,
    private val accentRgbProvider: () -> Int,
    private val translator: StringTranslator,
    private val logger: AppLogger,
    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("windows")
) : GuardEnginePort {
    private val _state = MutableStateFlow(GuardState())
    override val state: StateFlow<GuardState> = _state.asStateFlow()

    val attempts = GuardAttempts()

    @Volatile private var typed = ""
    @Volatile private var prompt: GuardPromptState = GuardPromptState.None
    @Volatile private var ownerMessage = ""
    @Volatile private var endedByWindows = false

    private var salt: ByteArray? = null
    private var hash: ByteArray? = null
    private val shutdownHookRegistered = AtomicBoolean(false)

    private val checker = Executors.newSingleThreadExecutor { Thread(it, "guard-password").apply { isDaemon = true } }
    private val powerButtons = PowerButtonManager(logger)
    private val blocker = WindowsInputBlocker(logger)
    private val overlayUi = GuardOverlayUi(languageProvider, accentRgbProvider, translator, logger)

    private fun readFile(): Properties = Properties().also { p ->
        try { if (propertiesFile.isFile) propertiesFile.inputStream().use { p.load(it) } } catch (e: Exception) { }
    }

    private fun writeFile(p: Properties) {
        propertiesFile.parentFile?.mkdirs()
        propertiesFile.outputStream().use {
            p.store(it, "One Touch Controller - Guard mode (the password is stored as a hash)")
        }
    }

    /** Call once when the program starts. Also repairs the power buttons if the program was closed while guarding. */
    override fun load() {
        val p = readFile()
        try {
            salt = p.getProperty("salt")?.let { Base64.getDecoder().decode(it) }
            hash = p.getProperty("hash")?.let { Base64.getDecoder().decode(it) }
        } catch (e: Exception) {
            salt = null
            hash = null
        }
        val hasPwd = salt != null && hash != null
        _state.update { it.copy(hasPassword = hasPwd) }
        if (isWindows && powerButtons.hasSavedValues(p)) {
            Thread { powerButtons.restore(::readFile, ::writeFile) }.start()
        }
        // if the program is closed while guarding, the power buttons are put back
        if (shutdownHookRegistered.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(Thread {
                if (_state.value.active) {
                    try { powerButtons.restore(::readFile, ::writeFile) } catch (e: Exception) { }
                }
            })
        }
    }

    /** Saves a new Guard password. Returns an error message, or null if it was saved. */
    override fun setPassword(text: String): String? {
        if (!GuardPassword.isValid(text)) return translator.tr("Use 4 to 32 letters and numbers only.")
        val newSalt = GuardPassword.newSalt()
        val newHash = GuardPassword.hash(text, newSalt)
        val p = readFile()
        p.setProperty("salt", Base64.getEncoder().encodeToString(newSalt))
        p.setProperty("hash", Base64.getEncoder().encodeToString(newHash))
        writeFile(p)
        salt = newSalt
        hash = newHash
        _state.update { it.copy(hasPassword = true) }
        return null
    }

    /** Turns Guard mode on. Throws an exception with a readable message if that is not possible. */
    @Synchronized
    override fun turnOn(who: String) {
        if (_state.value.active) return
        if (!isWindows) throw IllegalStateException("Guard mode works on Windows only")
        if (!_state.value.hasPassword) throw IllegalStateException("Set a guard password in the PC window first")
        blocker.startHooks(
            onPhysicalKey = ::physicalKey,
            onWindowsTookOver = ::windowsTookOver
        )
        try {
            overlayUi.showOverlay { GuardOverlaySnapshot(typed.length, prompt, ownerMessage) }
        } catch (e: Exception) {
            blocker.stopHooks()
            throw IllegalStateException("could not show the lock screen: ${e.message}")
        }
        typed = ""
        prompt = GuardPromptState.None
        ownerMessage = ""
        attempts.reset()
        endedByWindows = false
        blocker.blocking = true
        _state.update { it.copy(active = true) }
        logger.log("Guard mode ON ($who): the PC's own mouse and keyboard are blocked")
        blocker.startLockWatch(
            isStillActive = { _state.value.active },
            onWindowsTookOver = ::windowsTookOver
        )
        Thread {
            try {
                powerButtons.saveAndDisable(::readFile, ::writeFile)
            } catch (e: Exception) {
                logger.log("  Could not change the power button: ${e.message}")
            }
        }.start()
    }

    @Synchronized
    override fun turnOff(who: String) {
        if (!_state.value.active) return
        blocker.blocking = false
        blocker.stopHooks()
        try { overlayUi.hideOverlay() } catch (e: Exception) { }
        _state.update { it.copy(active = false) }
        typed = ""
        prompt = GuardPromptState.None
        ownerMessage = ""
        logger.log("Guard mode OFF ($who)")
        Thread {
            try { powerButtons.restore(::readFile, ::writeFile) } catch (e: Exception) { }
        }.start()
    }

    /**
     * Shows a message from the phone on this PC: on the lock screen while Guard mode is on,
     * otherwise in a small window at the top of the screen (closes with a click or after 20 seconds).
     */
    override fun showMessage(text: String) {
        val clean = text.replace(Regex("[\\p{Cntrl}&&[^\\n]]"), "").trim().take(200)
        if (clean.isEmpty()) return
        logger.log("Message from the phone shown on the PC: $clean")
        if (_state.value.active) {
            ownerMessage = clean
            overlayUi.repaintOverlay()
        } else {
            overlayUi.showPopup(clean)
        }
    }

    /** For the phone: "off", "off locked" (Windows locked the PC), "nopassword", or "on <wrong passwords typed at the PC>" */
    override fun statusText(): String = when {
        _state.value.active -> "on ${attempts.wrong}"
        !_state.value.hasPassword -> "nopassword"
        endedByWindows -> "off locked"
        else -> "off"
    }

    /**
     * Windows locked the PC or is going to sleep. Called from inside the Windows message loop, so the block is
     * lifted immediately (the person must be able to type the Windows password) and the rest is done on another thread.
     */
    private fun windowsTookOver(what: String) {
        if (!blocker.blocking) return
        blocker.blocking = false
        endedByWindows = true
        Thread { turnOff("$what - the Windows password protects the PC now") }.start()
    }

    // ---------- the person at the PC typing the password (runs inside the keyboard hook: must be quick) ----------
    private fun physicalKey(vk: Int) {
        val now = System.currentTimeMillis()
        val wait = attempts.secondsLeft(now)
        if (wait > 0) {
            prompt = GuardPromptState.TooManyWrong(wait)
        } else when (vk) {
            0x08 -> typed = typed.dropLast(1)                        // Backspace
            0x1B -> typed = ""                                       // Esc
            0x0D -> {                                                // Enter: check it on another thread (it is slow on purpose)
                val attempt = typed
                typed = ""
                prompt = GuardPromptState.Checking
                checker.execute { checkPassword(attempt) }
            }
            else -> guardKeyChar(vk)?.let {
                if (typed.length < GuardPassword.MAX_LENGTH) {
                    typed += it
                    prompt = GuardPromptState.None
                }
            }
        }
        overlayUi.repaintOverlay()
    }

    private fun checkPassword(attempt: String) {
        val s = salt
        val h = hash
        if (attempt.isNotEmpty() && s != null && h != null && GuardPassword.matches(attempt, s, h)) {
            turnOff("unlocked with the password at the PC")
            return
        }
        val waitStarted = attempts.recordWrong(System.currentTimeMillis())
        logger.log("Guard mode: someone typed a wrong password at the PC (${attempts.wrong} so far)")
        prompt = if (waitStarted) GuardPromptState.TooManyWrong(60) else GuardPromptState.WrongPassword
        overlayUi.repaintOverlay()
    }
}
