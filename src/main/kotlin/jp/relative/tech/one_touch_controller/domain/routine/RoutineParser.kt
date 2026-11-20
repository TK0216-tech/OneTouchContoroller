package jp.relative.tech.one_touch_controller.domain.routine

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
    val recName = Regex("^[A-Za-z0-9_-]+\\.rec$")      // only a file name: a recording is always in ~/.onetouch/recordings

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
            "replay" -> rest.takeIf { recName.matches(it) }?.let { Step.Replay(it) }
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

/**
 * Removes the routine [id] from the text of routines.txt: its "[id] Name" line and all its steps.
 * Notes (# lines) and empty lines that come after its last step are kept, because they usually belong
 * to whatever follows. Returns null if there is no routine with this id in the text.
 */
fun removeRoutineText(text: String, id: String): String? {
    val newline = if (text.contains("\r\n")) "\r\n" else "\n"
    val lines = text.split(Regex("\r?\n")).toMutableList()
    val header = Regex("^\\[([a-z0-9_-]{1,30})]")
    fun headerId(line: String) = header.find(line.trim().removePrefix("\uFEFF"))?.groupValues?.get(1)
    val start = lines.indexOfFirst { headerId(it) == id }
    if (start < 0) return null
    // the routine goes on until the next "[...]" line (the same rule the file reader uses)
    var next = start + 1
    while (next < lines.size && !lines[next].trim().startsWith("[")) next++
    var lastStep = start
    for (i in start + 1 until next) {
        val l = lines[i].trim()
        if (l.isNotEmpty() && !l.startsWith("#")) lastStep = i
    }
    repeat(lastStep - start + 1) { lines.removeAt(start) }
    // do not leave two empty lines next to each other
    if (start in 1 until lines.size && lines[start - 1].isBlank() && lines[start].isBlank()) lines.removeAt(start)
    return lines.joinToString(newline)
}
