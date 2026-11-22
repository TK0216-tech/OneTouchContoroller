// PC status for the phones: battery, charging, CPU and memory use (GET /status).

import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import java.lang.management.ManagementFactory

class PcStatus(
    val battery: Int,          // 0..100, or -1 = this PC has no battery (desktop)
    val charging: Boolean,
    val cpu: Int,              // 0..100 %, or -1 = unknown
    val memory: Int            // 0..100 % in use, or -1 = unknown
) {
    fun toJson(): String = """{"battery":$battery,"charging":$charging,"cpu":$cpu,"memory":$memory}"""
}

/** Windows' battery information (GetSystemPowerStatus). */
@Structure.FieldOrder("ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime")
class SystemPowerStatus : Structure() {
    @JvmField var ACLineStatus: Byte = 0           // 1 = plugged in
    @JvmField var BatteryFlag: Byte = 0            // 128 = no battery, 8 = charging
    @JvmField var BatteryLifePercent: Byte = 0     // 0..100, 255 = unknown
    @JvmField var SystemStatusFlag: Byte = 0
    @JvmField var BatteryLifeTime: Int = 0
    @JvmField var BatteryFullLifeTime: Int = 0
}

private interface Kernel32Power : StdCallLibrary {
    fun GetSystemPowerStatus(status: SystemPowerStatus): Boolean

    companion object {
        val INSTANCE: Kernel32Power by lazy { Native.load("kernel32", Kernel32Power::class.java) }
    }
}

/** Turns Windows' raw battery numbers into (percent or -1, charging). Pure logic, so it can be tested. */
fun batteryFrom(acLine: Int, flag: Int, percent: Int): Pair<Int, Boolean> {
    if (flag == 255 || (flag and 128) != 0 || percent !in 0..100) return -1 to (acLine == 1)
    return percent to (acLine == 1 || (flag and 8) != 0)
}

fun readPcStatus(): PcStatus {
    val (battery, charging) = try {
        val s = SystemPowerStatus()
        if (Kernel32Power.INSTANCE.GetSystemPowerStatus(s)) {
            batteryFrom(s.ACLineStatus.toInt() and 255, s.BatteryFlag.toInt() and 255, s.BatteryLifePercent.toInt() and 255)
        } else -1 to false
    } catch (e: Throwable) {
        -1 to false
    }
    var cpu = -1
    var memory = -1
    try {
        val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val load = os.cpuLoad
        if (load >= 0) cpu = (load * 100).toInt().coerceIn(0, 100)
        val total = os.totalMemorySize
        if (total > 0) memory = ((total - os.freeMemorySize) * 100 / total).toInt().coerceIn(0, 100)
    } catch (e: Throwable) {
        // not available on this Java: left as unknown
    }
    return PcStatus(battery, charging, cpu, memory)
}
