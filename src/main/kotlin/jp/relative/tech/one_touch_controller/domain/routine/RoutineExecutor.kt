package jp.relative.tech.one_touch_controller.domain.routine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// The real PC actions are plugged in from the platform layer, so this part can be tested without touching the PC.
interface RoutineActions {
    fun launch(program: String, args: List<String>)
    fun open(target: String)
    fun key(combo: String)
    fun close(exe: String)
    fun power(plan: String)
    fun command(id: String)
    fun replay(file: String)          // plays a recording; returns when it is finished
    fun valorant()
    fun log(message: String)
}

// Runs the steps one after another. A step that fails is reported and the routine goes on with the next one.
// Returns the number of failed steps.
suspend fun executeRoutine(routine: Routine, actions: RoutineActions): Int {
    val total = routine.steps.size
    var failed = 0
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
                is Step.Replay -> actions.replay(step.file)
                Step.Valorant -> actions.valorant()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed++
            actions.log("  This step failed: ${e.message}")
        }
    }
    actions.log("Routine \"${routine.name}\" finished")
    return failed
}
