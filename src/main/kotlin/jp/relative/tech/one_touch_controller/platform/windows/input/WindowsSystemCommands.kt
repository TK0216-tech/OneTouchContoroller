package jp.relative.tech.one_touch_controller.platform.windows.input

import jp.relative.tech.one_touch_controller.domain.ports.SystemCommandPort
import java.io.File

/**
 * Windows implementation of [SystemCommandPort] (process spawning, media keys, power plans, Riot Client detection).
 */
class WindowsSystemCommands(
    private val riotInstallsFile: File = File("C:\\ProgramData\\Riot Games\\RiotClientInstalls.json"),
    private val riotDefaultExe: File = File("C:\\Riot Games\\Riot Client\\RiotClientServices.exe")
) : SystemCommandPort {
    // Riot's own shortcut does exactly this: it starts the Riot Client and tells it to launch Valorant straight away.
    override val valorantArgs: List<String> = listOf("--launch-product=valorant", "--launch-patchline=live")

    override fun startProcess(vararg cmd: String) {
        ProcessBuilder(*cmd).start()
    }

    override fun launchProgram(program: String, args: List<String>) {
        val builder = ProcessBuilder(listOf(program) + args)
        File(program).parentFile?.takeIf { it.isDirectory }?.let { builder.directory(it) }   // many programs expect their own folder
        builder.start()
    }

    // rundll32 + FileProtocolHandler opens websites, files and folders the same way a double-click does
    override fun openTarget(target: String) {
        ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", target).start()
    }

    override fun closeProcess(exe: String) {
        ProcessBuilder("taskkill", "/IM", exe, "/F").redirectErrorStream(true).start().waitFor()
    }

    override fun setPowerPlan(plan: String) {
        val guid = when (plan) {
            "high" -> "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c"
            "saver" -> "a1841308-3541-4fab-bc81-f71556f20b4f"
            else -> "381b4222-f694-41f0-9685-ff5bb260df2e"      // balanced
        }
        val process = ProcessBuilder("powercfg", "/setactive", guid).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() != 0) throw RuntimeException("this PC has no \"$plan\" power plan (${output.take(80)})")
    }

    // Sends a media/volume key through Windows (175=vol up, 174=vol down, 173=mute,
    // 179=play/pause, 176=next track, 177=previous track)
    override fun sendMediaKey(code: Int) {
        startProcess("powershell", "-NoProfile", "-Command", "(New-Object -ComObject WScript.Shell).SendKeys([char]$code)")
    }

    override fun lockWorkstation() {
        startProcess("rundll32.exe", "user32.dll,LockWorkStation")
    }

    override fun shutdownIn(seconds: Int) {
        startProcess("shutdown", "/s", "/t", seconds.toString())
    }

    override fun restartIn(seconds: Int) {
        startProcess("shutdown", "/r", "/t", seconds.toString())
    }

    override fun cancelShutdown() {
        startProcess("shutdown", "/a")
    }

    override fun openInTextEditor(path: String) {
        ProcessBuilder("notepad.exe", path).start()
    }

    // Where Riot Client is installed: Riot writes the path into RiotClientInstalls.json, otherwise the usual folder.
    override fun findRiotClient(): String? {
        try {
            if (riotInstallsFile.isFile) {
                val match = Regex("\"rc_(?:default|live)\"\\s*:\\s*\"([^\"]+)\"").find(riotInstallsFile.readText())
                if (match != null) {
                    val path = match.groupValues[1].replace("\\\\", "\\").replace('/', File.separatorChar)
                    if (File(path).isFile) return path
                }
            }
        } catch (e: Exception) {
            // fall through to the default folder
        }
        return if (riotDefaultExe.isFile) riotDefaultExe.absolutePath else null
    }
}
