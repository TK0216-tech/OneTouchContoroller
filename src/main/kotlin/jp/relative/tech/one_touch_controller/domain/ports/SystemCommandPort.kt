package jp.relative.tech.one_touch_controller.domain.ports

/** Port for OS-level process execution, media keys, power plans and system control. */
interface SystemCommandPort {
    fun startProcess(vararg cmd: String)
    fun launchProgram(program: String, args: List<String>)
    fun openTarget(target: String)
    fun closeProcess(exe: String)
    fun setPowerPlan(plan: String)
    fun sendMediaKey(code: Int)
    fun lockWorkstation()
    fun shutdownIn(seconds: Int)
    fun restartIn(seconds: Int)
    fun cancelShutdown()
    fun openInTextEditor(path: String)
    fun findRiotClient(): String?
    val valorantArgs: List<String>
}
