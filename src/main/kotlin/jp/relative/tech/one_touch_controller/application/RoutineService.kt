package jp.relative.tech.one_touch_controller.application

import jp.relative.tech.one_touch_controller.domain.config.AppLogger
import jp.relative.tech.one_touch_controller.domain.ports.PcInputPort
import jp.relative.tech.one_touch_controller.domain.ports.RecordingPort
import jp.relative.tech.one_touch_controller.domain.ports.SystemCommandPort
import jp.relative.tech.one_touch_controller.domain.recording.RecEvent
import jp.relative.tech.one_touch_controller.domain.recording.recordingId
import jp.relative.tech.one_touch_controller.domain.recording.recordingSeconds
import jp.relative.tech.one_touch_controller.domain.routine.DEFAULT_ROUTINES
import jp.relative.tech.one_touch_controller.domain.routine.Routine
import jp.relative.tech.one_touch_controller.domain.routine.RoutineActions
import jp.relative.tech.one_touch_controller.domain.routine.Step
import jp.relative.tech.one_touch_controller.domain.routine.executeRoutine
import jp.relative.tech.one_touch_controller.domain.routine.parseRoutines
import jp.relative.tech.one_touch_controller.domain.routine.removeRoutineText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class RoutineState(
    val routines: List<Routine> = emptyList(),
    val problems: List<String> = emptyList(),
    val runningIds: Set<String> = emptySet()
)

/**
 * Manages loading, executing, deleting and saving recorded routines from `~/.onetouch/routines.txt`.
 * Shared by both the desktop UI and the Ktor server routes.
 */
class RoutineService(
    val routinesFile: File,
    private val commandRegistry: CommandRegistry,
    private val pcInput: PcInputPort,
    private val system: SystemCommandPort,
    private val recordingPort: RecordingPort,
    private val logger: AppLogger,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val _state = MutableStateFlow(RoutineState())
    val state: StateFlow<RoutineState> = _state.asStateFlow()

    // Ids of routines that are running right now (so one routine cannot be started twice at the same time)
    private val runningSet: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // How the last run of each routine ended: id -> number of steps that failed (0 = everything worked).
    // The phone asks for this (GET /routine/status) to show "Finished".
    private val routineResults = ConcurrentHashMap<String, Int>()

    private val actions = object : RoutineActions {
        override fun launch(program: String, args: List<String>) = system.launchProgram(program, args)
        override fun open(target: String) = system.openTarget(target)
        override fun key(combo: String) = pcInput.pressKeyCombo(combo)
        override fun close(exe: String) = system.closeProcess(exe)
        override fun power(plan: String) = system.setPowerPlan(plan)
        override fun command(id: String) {
            val def = commandRegistry.find(id) ?: throw IllegalArgumentException("unknown command \"$id\"")
            def.execute()
        }

        // Repeats mouse and keyboard actions that were recorded in the PC window (F9 stops it)
        override fun replay(file: String) {
            val events = recordingPort.load(file)
            if (events.isEmpty()) throw RuntimeException("the recording $file is empty")
            logger.log("  Playing ${events.size} recorded actions (about ${recordingSeconds(events)} s). Press F9 on the PC to stop.")
            if (!recordingPort.play(events)) logger.log("  Playback stopped with F9")
        }

        override fun valorant() {
            val riot = system.findRiotClient()
                ?: throw RuntimeException(
                    "Riot Client not found. Use:  launch \"<path to RiotClientServices.exe>\" --launch-product=valorant --launch-patchline=live"
                )
            launch(riot, system.valorantArgs)
        }

        override fun log(message: String) = logger.log(message)
    }

    /** Reads the routines file again. The first time, the file is created with a few examples. */
    fun reload() {
        try {
            if (!routinesFile.exists()) {
                routinesFile.parentFile?.mkdirs()
                routinesFile.writeText(DEFAULT_ROUTINES)
            }
            val parsed = parseRoutines(routinesFile.readText(), commandRegistry.knownIds, pcInput::isValidKeyCombo)
            _state.update { it.copy(routines = parsed.routines, problems = parsed.problems) }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    routines = emptyList(),
                    problems = listOf("Could not read ${routinesFile.absolutePath}: ${e.message}")
                )
            }
        }
        val current = _state.value
        logger.log(
            "Routines loaded: ${current.routines.size}" +
                if (current.problems.isEmpty()) "" else " (${current.problems.size} problem(s) in the file)"
        )
    }

    fun find(id: String?): Routine? = _state.value.routines.firstOrNull { it.id == id }

    fun isDangerous(routine: Routine): Boolean = routine.isDangerous(commandRegistry.dangerousIds)

    /** Returns "running", "finished <failed steps>" or "unknown" (for GET /routine/status). */
    fun statusText(id: String): String = when {
        id in runningSet -> "running"
        routineResults.containsKey(id) -> "finished ${routineResults[id]}"
        else -> "unknown"
    }

    /**
     * Starts a routine in the background. Used by the phone (/routine/run) and by the Run buttons in the PC window.
     * Returns the message for the phone: "Started: ..." or "Already running: ...".
     */
    fun start(routine: Routine, from: String): String {
        if (!runningSet.add(routine.id)) return "Already running: ${routine.name}"
        _state.update { it.copy(runningIds = it.runningIds + routine.id) }
        logger.log("Routine started ($from): ${routine.name}")
        routineResults.remove(routine.id)
        scope.launch {
            var failed = 0
            try {
                failed = executeRoutine(routine, actions)
            } finally {
                routineResults[routine.id] = failed        // first the result, then "not running" (no gap for the phone)
                runningSet.remove(routine.id)
                _state.update { it.copy(runningIds = it.runningIds - routine.id) }
            }
        }
        return "Started: ${routine.name}"
    }

    /**
     * Deletes a routine from routines.txt (and its recording, if no other routine uses it).
     * Returns an error message, or null when it worked.
     */
    fun delete(routine: Routine): String? {
        if (routine.id in runningSet) return "\"${routine.name}\" is running. Wait until it has finished."
        return try {
            val text = routinesFile.readText()
            val newText = removeRoutineText(text, routine.id)
                ?: return "\"${routine.name}\" is not in the routines file any more. Press Reload."
            routinesFile.writeText(newText)
            reload()
            // a recording that no other routine uses is deleted too
            val stillUsed = _state.value.routines
                .flatMap { r -> r.steps.filterIsInstance<Step.Replay>().map { it.file } }
                .toSet()
            routine.steps.filterIsInstance<Step.Replay>()
                .map { it.file }
                .filter { it !in stillUsed }
                .forEach { fileName -> recordingPort.delete(fileName) }
            logger.log("Routine deleted: ${routine.name}")
            null
        } catch (e: Exception) {
            "Could not change the routines file: ${e.message}"
        }
    }

    /** Saves recorded [events] as a new routine entry in routines.txt and reloads the list. */
    fun saveRecordingAsRoutine(rawName: String, events: List<RecEvent>) {
        val name = rawName.trim()
        val taken = _state.value.routines.map { it.id }.toSet() + recordingPort.listRecordingIds()
        val id = recordingId(name, taken)
        recordingPort.save(id, events)
        routinesFile.parentFile?.mkdirs()
        routinesFile.appendText("\n[$id] $name\nreplay $id.rec\n")
        reload()
        logger.log("Saved the recorded routine \"$name\"")
    }

    fun openRoutinesFile() {
        system.openInTextEditor(routinesFile.absolutePath)
    }
}
