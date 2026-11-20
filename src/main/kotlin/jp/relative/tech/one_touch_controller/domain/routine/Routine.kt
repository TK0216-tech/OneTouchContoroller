package jp.relative.tech.one_touch_controller.domain.routine

// Routines = one tap on the phone runs a whole list of steps on the PC (for example "get ready for Valorant").
//
// The steps are written ONCE, on the PC, in the text file  ~/.onetouch/routines.txt  (see DEFAULT_ROUTINES below).
// The phone only sends the NAME of a routine. It can never send its own steps, so a stolen token still cannot
// make the PC run anything that you did not write into that file yourself.

sealed class Step {
    data class Launch(val program: String, val args: List<String>) : Step()   // launch "C:\\path\\app.exe" args
    data class Open(val target: String) : Step()                              // open https://... or a file/folder
    data class Wait(val seconds: Int) : Step()                                // wait 5
    data class Key(val combo: String) : Step()                                // key ctrl+shift+m
    data class Close(val exe: String) : Step()                                // close discord.exe
    data class Power(val plan: String) : Step()                               // power high | balanced | saver
    data class Command(val id: String) : Step()                               // command mute  (a phone command button)
    data class Replay(val file: String) : Step()                              // replay my-task.rec  (a recorded routine)
    object Valorant : Step()                                                  // valorant
}

class Routine(val id: String, val name: String, val steps: List<Step>) {
    // Routines that contain shutdown / restart / script commands follow the same internet rule as those commands
    fun isDangerous(dangerous: Set<String>): Boolean = steps.any { it is Step.Command && it.id in dangerous }
}

class ParsedRoutines(val routines: List<Routine>, val problems: List<String>)

fun describeStep(step: Step): String = when (step) {
    is Step.Launch -> "launch " + (listOf(step.program) + step.args).joinToString(" ")
    is Step.Open -> "open " + step.target
    is Step.Wait -> "wait ${step.seconds} s"
    is Step.Key -> "key " + step.combo
    is Step.Close -> "close " + step.exe
    is Step.Power -> "power " + step.plan
    is Step.Command -> "command " + step.id
    is Step.Replay -> "replay " + step.file
    Step.Valorant -> "start Valorant"
}

const val MAX_ROUTINES = 30
const val MAX_STEPS = 40

// ---------- The file that is created on first start ----------
val DEFAULT_ROUTINES = """# One Touch Controller - routines
#
# A routine is a list of steps that runs on this PC when you tap its button on your phone.
# Start a routine with  [id] Name  and write one step per line below it.
# Lines that start with # are notes. After saving this file, press "Reload" in the PC window.
#
# Steps you can use:
#   valorant                        start Valorant through the Riot Client (it finds the program by itself)
#   launch "C:\path\app.exe" args   start any program (put the path in quotes if it has spaces)
#   open https://example.com        open a website, a file or a folder
#   wait 5                          wait 5 seconds (0 to 300)
#   key ctrl+shift+m                press a key or shortcut
#   close discord.exe               close a program by its .exe name
#   power high                      power plan: high, balanced or saver
#   command mute                    do the same as a phone command button (mute, volume_up, ...)
#   replay my-task.rec              repeat mouse and keyboard actions you recorded with "Record" in the PC window

[valorant] Valorant ready
power high
valorant

[normal] Back to normal
power balanced

# Example (remove the # signs to try it): open Discord, then Chrome
# [chat] Discord and Chrome
# launch "C:\Users\YourName\AppData\Local\Discord\Update.exe" --processStart Discord.exe
# wait 3
# open https://www.google.com
"""
