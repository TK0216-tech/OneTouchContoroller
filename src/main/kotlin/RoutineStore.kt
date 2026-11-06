// Keeps the routines that were loaded from ~/.onetouch/routines.txt
// (the window and the phone both read from here).

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object RoutineStore {
    val file = File(System.getProperty("user.home"), ".onetouch/routines.txt")

    // The window watches these two and redraws by itself when they change
    var routines by mutableStateOf<List<Routine>>(emptyList())
        private set
    var problems by mutableStateOf<List<String>>(emptyList())
        private set

    // Ids of routines that are running right now (so one routine cannot be started twice at the same time)
    val running: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Reads the file again. The first time, the file is created with a few examples. */
    fun load(knownCommands: Set<String>, isValidKeyCombo: (String) -> Boolean) {
        try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText(DEFAULT_ROUTINES)
            }
            val parsed = parseRoutines(file.readText(), knownCommands, isValidKeyCombo)
            routines = parsed.routines
            problems = parsed.problems
        } catch (e: Exception) {
            routines = emptyList()
            problems = listOf("Could not read ${file.absolutePath}: ${e.message}")
        }
    }

    fun find(id: String?): Routine? = routines.firstOrNull { it.id == id }
}
