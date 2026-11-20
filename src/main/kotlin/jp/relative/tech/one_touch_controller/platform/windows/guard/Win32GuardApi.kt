package jp.relative.tech.one_touch_controller.platform.windows.guard

import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

// Windows functions that JNA's default User32 interface does not declare.
internal interface User32Extra : StdCallLibrary {
    fun SetWindowDisplayAffinity(hWnd: HWND, affinity: Int): Boolean
    fun ShutdownBlockReasonCreate(hWnd: HWND, reason: WString): Boolean
    fun ShutdownBlockReasonDestroy(hWnd: HWND): Boolean

    companion object {
        val INSTANCE: User32Extra by lazy {
            Native.load("user32", User32Extra::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }
}

internal object Win32GuardConstants {
    const val WM_QUIT = 0x0012
    const val WM_QUERYENDSESSION = 0x0011
    const val WM_WTSSESSION_CHANGE = 0x02B1     // Windows was locked / unlocked
    const val WM_POWERBROADCAST = 0x0218         // the PC is going to sleep
    const val PBT_APMSUSPEND = 0x0004
    const val WM_KEYDOWN = 0x0100
    const val WM_SYSKEYDOWN = 0x0104
    const val LLKHF_INJECTED = 0x10 or 0x02      // made by a program (+ "from a lower level program")
    const val LLMHF_INJECTED = 0x01 or 0x02
    const val ES_CONTINUOUS = 0x80000000.toInt()
    const val ES_SYSTEM_REQUIRED = 0x00000001
    const val ES_DISPLAY_REQUIRED = 0x00000002
    const val WS_EX_LAYERED = 0x00080000
    const val WS_EX_TRANSPARENT = 0x00000020
    const val WS_EX_TOOLWINDOW = 0x00000080
    const val WS_EX_NOACTIVATE = 0x08000000
    const val WS_EX_TOPMOST = 0x00000008
    const val GWL_EXSTYLE = -20
    const val WDA_EXCLUDEFROMCAPTURE = 0x11
    const val SWP_NOACTIVATE = 0x0010
    const val SWP_SHOWWINDOW = 0x0040
}
