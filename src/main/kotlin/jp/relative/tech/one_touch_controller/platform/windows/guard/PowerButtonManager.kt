package jp.relative.tech.one_touch_controller.platform.windows.guard

import jp.relative.tech.one_touch_controller.domain.config.AppLogger
import jp.relative.tech.one_touch_controller.domain.security.parsePowercfgValues
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Temporarily sets Windows power, sleep and lid buttons to "do nothing" while Guard mode is active,
 * storing the original AC/DC indices in `guard.properties` so they are always restored afterwards.
 */
internal class PowerButtonManager(
    private val logger: AppLogger
) {
    private val powerSettings = listOf("PBUTTONACTION", "SBUTTONACTION", "LIDACTION")

    fun hasSavedValues(properties: Properties): Boolean =
        powerSettings.any { properties.getProperty("power.$it") != null }

    @Synchronized
    fun saveAndDisable(readProperties: () -> Properties, writeProperties: (Properties) -> Unit) {
        val p = readProperties()
        var changed = false
        for (name in powerSettings) {
            if (p.getProperty("power.$name") == null) {          // keep the very first original value
                val values = parsePowercfgValues(powercfg("/query", "SCHEME_CURRENT", "SUB_BUTTONS", name)) ?: continue
                p.setProperty("power.$name", "${values.first},${values.second}")
            }
            powercfg("/setacvalueindex", "SCHEME_CURRENT", "SUB_BUTTONS", name, "0")     // 0 = do nothing
            powercfg("/setdcvalueindex", "SCHEME_CURRENT", "SUB_BUTTONS", name, "0")
            changed = true
        }
        writeProperties(p)
        if (changed) {
            powercfg("/setactive", "SCHEME_CURRENT")
            logger.log("  Power, sleep and lid buttons set to \"do nothing\" while guarding")
        }
    }

    @Synchronized
    fun restore(readProperties: () -> Properties, writeProperties: (Properties) -> Unit) {
        val p = readProperties()
        var changed = false
        for (name in powerSettings) {
            val saved = p.getProperty("power.$name") ?: continue
            val parts = saved.split(",")
            if (parts.size == 2) {
                powercfg("/setacvalueindex", "SCHEME_CURRENT", "SUB_BUTTONS", name, parts[0])
                powercfg("/setdcvalueindex", "SCHEME_CURRENT", "SUB_BUTTONS", name, parts[1])
                changed = true
            }
            p.remove("power.$name")
        }
        writeProperties(p)
        if (changed) powercfg("/setactive", "SCHEME_CURRENT")
    }

    private fun powercfg(vararg args: String): String {
        val p = ProcessBuilder(listOf("powercfg") + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        p.waitFor(10, TimeUnit.SECONDS)
        return out
    }
}
