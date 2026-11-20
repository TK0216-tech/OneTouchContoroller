package jp.relative.tech.one_touch_controller.application

import jp.relative.tech.one_touch_controller.domain.ports.GuardEnginePort
import jp.relative.tech.one_touch_controller.domain.ports.PcInputPort
import jp.relative.tech.one_touch_controller.domain.ports.SystemCommandPort

/**
 * Single definition of a whitelisted remote command, including whether it is restricted over the internet.
 */
data class CommandDefinition(
    val id: String,
    val dangerousOverInternet: Boolean = false,
    val execute: () -> String
)

/**
 * Registry of all whitelisted commands the phone can run.
 * Keeps the command action and its `dangerousOverInternet` flag together in one place so they never drift apart.
 */
class CommandRegistry(definitions: List<CommandDefinition>) {
    private val byId: Map<String, CommandDefinition> = definitions.associateBy { it.id }

    val knownIds: Set<String> = byId.keys
    val dangerousIds: Set<String> = definitions.filter { it.dangerousOverInternet }.map { it.id }.toSet()

    fun find(id: String?): CommandDefinition? = if (id == null) null else byId[id]

    companion object {
        fun default(
            system: SystemCommandPort,
            pcInput: PcInputPort,
            guardEngine: GuardEnginePort
        ): CommandRegistry = CommandRegistry(
            listOf(
                // Programs
                CommandDefinition("open_notepad") { system.startProcess("notepad.exe"); "Opened Notepad" },
                CommandDefinition("open_calculator") { system.startProcess("calc.exe"); "Opened Calculator" },
                CommandDefinition("open_explorer") { system.startProcess("explorer.exe"); "Opened File Explorer" },
                CommandDefinition("open_chrome") { system.startProcess("cmd", "/c", "start", "", "chrome"); "Opened Chrome" },

                // Websites
                CommandDefinition("open_youtube") { system.startProcess("cmd", "/c", "start", "", "https://www.youtube.com"); "Opened YouTube" },
                CommandDefinition("open_google") { system.startProcess("cmd", "/c", "start", "", "https://www.google.com"); "Opened Google" },

                // Your own automation script (change the path!)
                CommandDefinition("run_script_1", dangerousOverInternet = true) {
                    system.startProcess("cmd", "/c", "start", "", "C:\\scripts\\script1.bat")
                    "Ran script 1"
                },

                // Media and volume
                CommandDefinition("volume_up") { system.sendMediaKey(175); "Volume up" },
                CommandDefinition("volume_down") { system.sendMediaKey(174); "Volume down" },
                CommandDefinition("mute") { system.sendMediaKey(173); "Mute toggled" },
                CommandDefinition("play_pause") { system.sendMediaKey(179); "Play/Pause" },
                CommandDefinition("next_track") { system.sendMediaKey(176); "Next track" },
                CommandDefinition("prev_track") { system.sendMediaKey(177); "Previous track" },

                // System
                CommandDefinition("lock_screen") { system.lockWorkstation(); "PC locked" },
                CommandDefinition("screenshot") { pcInput.takeScreenshot() },
                // (Guard mode refuses shutdowns, so it is turned off first when YOU ask for one from the phone)
                CommandDefinition("shutdown_60", dangerousOverInternet = true) {
                    guardEngine.turnOff("shutdown from the phone")
                    system.shutdownIn(60)
                    "Shutting down in 60 seconds"
                },
                CommandDefinition("restart_60", dangerousOverInternet = true) {
                    guardEngine.turnOff("restart from the phone")
                    system.restartIn(60)
                    "Restarting in 60 seconds"
                },
                CommandDefinition("cancel_shutdown") { system.cancelShutdown(); "Shutdown/restart cancelled" }
            )
        )
    }
}
