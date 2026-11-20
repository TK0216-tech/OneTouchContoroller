package jp.relative.tech.one_touch_controller.platform.windows.guard

import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HMODULE
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.platform.win32.Wtsapi32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import jp.relative.tech.one_touch_controller.domain.config.AppLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Installs Win32 low-level keyboard/mouse hooks (swallowing physical input while letting injected remote input pass),
 * a hidden shutdown-blocker window, and a backup WTS session-lock watcher.
 */
internal class WindowsInputBlocker(
    private val logger: AppLogger
) {
    @Volatile var blocking: Boolean = false

    @Volatile private var hookThreadId = 0
    private var hookThread: Thread? = null

    // The hooks and window proc MUST stay referenced on this object, or Java GC could collect them while Windows still calls them.
    private var keyboardHook: WinUser.HHOOK? = null
    private var mouseHook: WinUser.HHOOK? = null
    private var keyboardProc: WinUser.LowLevelKeyboardProc? = null
    private var mouseProc: WinUser.LowLevelMouseProc? = null
    private var shutdownWindowProc: WinUser.WindowProc? = null

    fun startHooks(
        onPhysicalKey: (Int) -> Unit,
        onWindowsTookOver: (String) -> Unit
    ) {
        val started = CountDownLatch(1)
        var problem: String? = null
        val thread = Thread({
            val user32 = User32.INSTANCE
            val kernel32 = Kernel32.INSTANCE
            hookThreadId = kernel32.GetCurrentThreadId()
            val module = kernel32.GetModuleHandle(null)

            keyboardProc = object : WinUser.LowLevelKeyboardProc {
                override fun callback(nCode: Int, wParam: WPARAM, info: WinUser.KBDLLHOOKSTRUCT): LRESULT {
                    if (nCode >= 0 && blocking && (info.flags and Win32GuardConstants.LLKHF_INJECTED) == 0) {
                        val msg = wParam.toInt()
                        if (msg == Win32GuardConstants.WM_KEYDOWN || msg == Win32GuardConstants.WM_SYSKEYDOWN) {
                            onPhysicalKey(info.vkCode)
                        }
                        return LRESULT(1)                                        // swallow the real key
                    }
                    return user32.CallNextHookEx(keyboardHook, nCode, wParam, LPARAM(Pointer.nativeValue(info.pointer)))
                }
            }
            mouseProc = object : WinUser.LowLevelMouseProc {
                override fun callback(nCode: Int, wParam: WPARAM, info: WinUser.MSLLHOOKSTRUCT): LRESULT {
                    if (nCode >= 0 && blocking && (info.flags and Win32GuardConstants.LLMHF_INJECTED) == 0) {
                        return LRESULT(1)                                        // swallow the real mouse
                    }
                    return user32.CallNextHookEx(mouseHook, nCode, wParam, LPARAM(Pointer.nativeValue(info.pointer)))
                }
            }
            keyboardHook = user32.SetWindowsHookEx(WinUser.WH_KEYBOARD_LL, keyboardProc, module, 0)
            mouseHook = user32.SetWindowsHookEx(WinUser.WH_MOUSE_LL, mouseProc, module, 0)
            if (keyboardHook == null || mouseHook == null) {
                problem = "Windows refused to block the mouse and keyboard (error ${kernel32.GetLastError()})"
                keyboardHook?.let { user32.UnhookWindowsHookEx(it) }
                mouseHook?.let { user32.UnhookWindowsHookEx(it) }
                keyboardHook = null
                mouseHook = null
                started.countDown()
                return@Thread
            }

            // A hidden window that answers "no" when Windows asks "may I shut down?"
            val shutdownWindow = try {
                createShutdownBlocker(module, onWindowsTookOver)
            } catch (e: Exception) {
                null
            }
            // keep the PC (and the screen) awake while guarding
            kernel32.SetThreadExecutionState(
                Win32GuardConstants.ES_CONTINUOUS or
                    Win32GuardConstants.ES_SYSTEM_REQUIRED or
                    Win32GuardConstants.ES_DISPLAY_REQUIRED
            )
            started.countDown()

            val msg = WinUser.MSG()
            while (user32.GetMessage(msg, null, 0, 0) > 0) {
                user32.TranslateMessage(msg)
                user32.DispatchMessage(msg)
            }

            // Guard mode is over: clean up
            user32.UnhookWindowsHookEx(keyboardHook)
            user32.UnhookWindowsHookEx(mouseHook)
            keyboardHook = null
            mouseHook = null
            if (shutdownWindow != null) {
                try { Wtsapi32.INSTANCE.WTSUnRegisterSessionNotification(shutdownWindow) } catch (e: Throwable) { }
                try { User32Extra.INSTANCE.ShutdownBlockReasonDestroy(shutdownWindow) } catch (e: Throwable) { }
                user32.DestroyWindow(shutdownWindow)
            }
            kernel32.SetThreadExecutionState(Win32GuardConstants.ES_CONTINUOUS)
        }, "guard-hooks")
        thread.isDaemon = true
        thread.start()
        if (!started.await(5, TimeUnit.SECONDS)) {
            throw IllegalStateException("blocking the mouse and keyboard did not start")
        }
        problem?.let { throw IllegalStateException(it) }
        hookThread = thread
    }

    fun stopHooks() {
        val id = hookThreadId
        if (id != 0) User32.INSTANCE.PostThreadMessage(id, Win32GuardConstants.WM_QUIT, WPARAM(0), LPARAM(0))
        hookThread?.join(2000)
        hookThread = null
        hookThreadId = 0
    }

    /**
     * Backup check every 2 seconds: is Windows locked? (Normally Windows tells us at once via the hidden window.
     * This catches the case where that message is missed, so the lock screen can never stay blocked.)
     */
    fun startLockWatch(isStillActive: () -> Boolean, onWindowsTookOver: (String) -> Unit) {
        Thread({
            // The check must say "unlocked" right now (you just turned Guard mode on at an unlocked PC).
            // If it does not, it cannot be trusted on this PC, and only Windows' own lock message is used.
            if (lockState() != 1) {
                logger.log("  (The backup lock check does not work on this PC; Guard mode uses Windows' lock message only)")
                return@Thread
            }
            var lockedReadings = 0
            while (isStillActive() && blocking) {
                lockedReadings = if (lockState() == 0) lockedReadings + 1 else 0
                if (lockedReadings >= 2) {                       // "locked" twice in a row, 2 seconds apart
                    onWindowsTookOver("Windows locked the PC")
                    return@Thread
                }
                try {
                    Thread.sleep(2000)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }, "guard-lock-watch").apply { isDaemon = true }.start()
    }

    /** 0 = Windows is locked, 1 = unlocked, null = could not tell. */
    private fun lockState(): Int? = try {
        val wts = Wtsapi32.INSTANCE
        val buffer = PointerByReference()
        if (!wts.WTSQuerySessionInformation(
                Wtsapi32.WTS_CURRENT_SERVER_HANDLE,
                Wtsapi32.WTS_CURRENT_SESSION,
                Wtsapi32.WTS_INFO_CLASS.WTSSessionInfoEx,
                buffer,
                IntByReference()
            )
        ) {
            null
        } else {
            // WTSINFOEX: Level (4 bytes) + 4 bytes padding (the data part starts at 8 because it holds 8-byte numbers),
            // then SessionId (4), SessionState (4), SessionFlags (4) -> SessionFlags is at byte 16.
            val level = buffer.value.getInt(0)
            val flags = buffer.value.getInt(16)
            wts.WTSFreeMemory(buffer.value)
            if (level == 1 && (flags == 0 || flags == 1)) flags else null
        }
    } catch (e: Throwable) {
        null
    }

    private fun createShutdownBlocker(module: HMODULE, onWindowsTookOver: (String) -> Unit): HWND? {
        val user32 = User32.INSTANCE
        val className = "OneTouchGuardShutdownBlocker"
        shutdownWindowProc = object : WinUser.WindowProc {
            override fun callback(hwnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT {
                if (uMsg == Win32GuardConstants.WM_QUERYENDSESSION && blocking) {
                    logger.log("Guard mode: a shutdown or sign-out was started at the PC and was refused")
                    return LRESULT(0)                        // "no, do not shut down"
                }
                if (uMsg == Win32GuardConstants.WM_WTSSESSION_CHANGE && wParam.toInt() == Wtsapi32.WTS_SESSION_LOCK) {
                    onWindowsTookOver("Windows locked the PC (power button, Win+L or Ctrl+Alt+Del)")
                }
                if (uMsg == Win32GuardConstants.WM_POWERBROADCAST && wParam.toInt() == Win32GuardConstants.PBT_APMSUSPEND) {
                    onWindowsTookOver("the PC is going to sleep")
                }
                return user32.DefWindowProc(hwnd, uMsg, wParam, lParam)
            }
        }
        val wc = WinUser.WNDCLASSEX()
        wc.hInstance = module
        wc.lpfnWndProc = shutdownWindowProc
        wc.lpszClassName = className
        user32.RegisterClassEx(wc)                           // already registered from an earlier time = fine
        val hwnd = user32.CreateWindowEx(0, className, "One Touch Controller guard", 0, 0, 0, 0, 0, null, null, module, null)
            ?: return null
        try {
            User32Extra.INSTANCE.ShutdownBlockReasonCreate(hwnd, WString("This PC is in Guard mode (One Touch Controller)."))
        } catch (e: Throwable) {
            // older Windows: the window still refuses, only without a reason text
        }
        // tell this window when Windows locks the PC
        val told = try {
            Wtsapi32.INSTANCE.WTSRegisterSessionNotification(hwnd, Wtsapi32.NOTIFY_FOR_THIS_SESSION)
        } catch (e: Throwable) {
            false
        }
        if (!told) logger.log("  Guard mode cannot see when Windows locks the PC: if it locks, turn Guard mode off from your phone")
        return hwnd
    }
}
