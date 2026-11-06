// Routines = one tap on the phone runs a whole list of steps on the PC (for example "get ready for Valorant").
//
// The steps are written ONCE, on the PC, in the text file  ~/.onetouch/routines.txt  (see DEFAULT_ROUTINES below).
// The phone only sends the NAME of a routine. It can never send its own steps, so a stolen token still cannot
// make the PC run anything that you did not write into that file yourself.

import kotlinx.coroutines.delay
import java.io.File

// ---------- The steps ----------
sealed class Step {
    data class Launch(val program: String, val args: List<String>) : Step()   // launch "C:\\path\\app.exe" args
    data class Open(val target: String) : Step()                              // open https://... or a file/folder
    data class Wait(val seconds: Int) : Step()                                // wait 5
    data class Key(val combo: String) : Step()                                // key ctrl+shift+m
    data class Close(val exe: String) : Step()                                // close discord.exe
    data class Power(val plan: String) : Step()                               // power high | balanced | saver
    data class Command(val id: String) : Step()                               // command mute  (a phone command button)
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
    Step.Valorant -> "start Valorant"
}

// Splits  launch "C:\\Program Files\\X\\x.exe" --flag  into its parts. Words in "quotes" stay together.
fun splitArgs(text: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var hasContent = false
    for (c in text) {
        when {
            c == '"' -> { inQuotes = !inQuotes; hasContent = true }
            c.isWhitespace() && !inQuotes -> {
                if (hasContent) { result.add(current.toString()); current.clear(); hasContent = false }
            }
            else -> { current.append(c); hasContent = true }
        }
    }
    if (hasContent) result.add(current.toString())
    return result
}

const val MAX_ROUTINES = 30
const val MAX_STEPS = 40

// Reads the routines file. Mistakes never stop the whole file from loading: a bad line is skipped and
// reported in `problems` (with its line number) so it can be shown in the PC window.
fun parseRoutines(
    text: String,
    knownCommands: Set<String>,
    isValidKeyCombo: (String) -> Boolean
): ParsedRoutines {
    val routines = mutableListOf<Routine>()
    val problems = mutableListOf<String>()
    var id: String? = null
    var name = ""
    var steps = mutableListOf<Step>()
    var headerLine = 0

    fun finishRoutine() {
        val currentId = id ?: return
        when {
            steps.isEmpty() -> problems.add("Line $headerLine: routine [$currentId] has no steps, so it was skipped")
            routines.any { it.id == currentId } -> problems.add("Line $headerLine: the id [$currentId] is used twice, the second one was skipped")
            routines.size >= MAX_ROUTINES -> problems.add("Line $headerLine: too many routines (maximum $MAX_ROUTINES)")
            else -> routines.add(Routine(currentId, name, steps.toList()))
        }
        id = null
        steps = mutableListOf()
    }

    val header = Regex("^\\[([a-z0-9_-]{1,30})]\\s*(.*)$")
    val exeName = Regex("^[A-Za-z0-9._-]+\\.exe$")

    text.lines().forEachIndexed { index, rawLine ->
        val lineNo = index + 1
        val line = rawLine.trim().removePrefix("\uFEFF")          // \uFEFF = invisible mark some editors add at the start
        if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

        if (line.startsWith("[")) {
            finishRoutine()
            val match = header.find(line)
            if (match == null) {
                problems.add("Line $lineNo: a routine header looks like  [id] Name  (id: a-z, 0-9, - or _)")
            } else {
                id = match.groupValues[1]
                name = match.groupValues[2].ifBlank { match.groupValues[1] }
                headerLine = lineNo
            }
            return@forEachIndexed
        }

        if (id == null) {
            problems.add("Line $lineNo: this step is not inside a routine (start with a [id] Name line)")
            return@forEachIndexed
        }
        if (steps.size >= MAX_STEPS) {
            problems.add("Line $lineNo: too many steps in [$id] (maximum $MAX_STEPS)")
            return@forEachIndexed
        }

        val word = line.substringBefore(' ').lowercase()
        val rest = line.substringAfter(' ', "").trim()
        val step: Step? = when (word) {
            "valorant" -> Step.Valorant
            "launch" -> splitArgs(rest).let { if (it.isEmpty()) null else Step.Launch(it[0], it.drop(1)) }
            "open" -> rest.removeSurrounding("\"").ifBlank { null }?.let { Step.Open(it) }
            "wait" -> rest.toIntOrNull()?.takeIf { it in 0..300 }?.let { Step.Wait(it) }
            "key" -> rest.lowercase().takeIf { it.isNotEmpty() && isValidKeyCombo(it) }?.let { Step.Key(it) }
            "close" -> rest.takeIf { exeName.matches(it) }?.let { Step.Close(it) }
            "power" -> rest.lowercase().takeIf { it in setOf("high", "balanced", "saver") }?.let { Step.Power(it) }
            "command" -> rest.takeIf { it in knownCommands }?.let { Step.Command(it) }
            else -> {
                problems.add("Line $lineNo: unknown step \"$word\"")
                return@forEachIndexed
            }
        }
        if (step == null) problems.add("Line $lineNo: \"$line\" is not valid (check the value after \"$word\")")
        else steps.add(step)
    }
    finishRoutine()
    return ParsedRoutines(routines, problems)
}

// ---------- Running a routine ----------
// The real PC actions are plugged in from Main.kt, so this part can be tested without touching the PC.
interface RoutineActions {
    fun launch(program: String, args: List<String>)
    fun open(target: String)
    fun key(combo: String)
    fun close(exe: String)
    fun power(plan: String)
    fun command(id: String)
    fun valorant()
    fun log(message: String)
}

// Runs the steps one after another. A step that fails is reported and the routine goes on with the next one.
suspend fun executeRoutine(routine: Routine, actions: RoutineActions) {
    val total = routine.steps.size
    routine.steps.forEachIndexed { index, step ->
        actions.log("Routine \"${routine.name}\" (${index + 1}/$total): ${describeStep(step)}")
        try {
            when (step) {
                is Step.Launch -> actions.launch(step.program, step.args)
                is Step.Open -> actions.open(step.target)
                is Step.Wait -> delay(step.seconds * 1000L)
                is Step.Key -> actions.key(step.combo)
                is Step.Close -> actions.close(step.exe)
                is Step.Power -> actions.power(step.plan)
                is Step.Command -> actions.command(step.id)
                Step.Valorant -> actions.valorant()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            actions.log("  This step failed: ${e.message}")
        }
    }
    actions.log("Routine \"${routine.name}\" finished")
}

// ---------- Valorant ----------
// Riot's own shortcut does exactly this: it starts the Riot Client and tells it to launch Valorant straight
// away, so no clicking is needed. (If "Stay signed in" is off, the Riot Client still waits for your login.)
val VALORANT_ARGS = listOf("--launch-product=valorant", "--launch-patchline=live")

// Where Riot Client is installed: Riot writes the path into RiotClientInstalls.json, otherwise the usual folder.
fun findRiotClient(
    installsFile: File = File("C:\\ProgramData\\Riot Games\\RiotClientInstalls.json"),
    defaultExe: File = File("C:\\Riot Games\\Riot Client\\RiotClientServices.exe")
): String? {
    try {
        if (installsFile.isFile) {
            val match = Regex("\"rc_(?:default|live)\"\\s*:\\s*\"([^\"]+)\"").find(installsFile.readText())
            if (match != null) {
                val path = match.groupValues[1].replace("\\\\", "\\").replace('/', File.separatorChar)
                if (File(path).isFile) return path
            }
        }
    } catch (e: Exception) {
        // fall through to the default folder
    }
    return if (defaultExe.isFile) defaultExe.absolutePath else null
}

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
