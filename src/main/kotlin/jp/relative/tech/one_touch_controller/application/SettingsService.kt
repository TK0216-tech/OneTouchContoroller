package jp.relative.tech.one_touch_controller.application

import jp.relative.tech.one_touch_controller.domain.config.AppLanguage
import jp.relative.tech.one_touch_controller.domain.config.AppSettings
import jp.relative.tech.one_touch_controller.domain.config.DEFAULT_ACCENTS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import java.util.Properties

/**
 * Loads and saves window appearance settings (`~/.onetouch/settings.properties`) and publishes changes as [StateFlow].
 */
class SettingsService(
    private val file: File,
    private val defaultLocaleProvider: () -> Locale = Locale::getDefault
) {
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    fun load() {
        // first start: use the language of Windows
        val defaultLang = when (defaultLocaleProvider().language) {
            "ja" -> AppLanguage.JA
            "zh" -> AppLanguage.ZH
            else -> AppLanguage.EN
        }
        var dark = true
        var accent = 0
        var lang = defaultLang
        try {
            if (file.isFile) {
                val p = Properties()
                file.inputStream().use { p.load(it) }
                dark = p.getProperty("dark", "true") != "false"
                accent = (p.getProperty("accent", "0").toIntOrNull() ?: 0).coerceIn(DEFAULT_ACCENTS.indices)
                lang = AppLanguage.fromCode(p.getProperty("lang"), fallback = defaultLang)
            }
        } catch (e: Exception) {
            // a broken settings file is not a reason to stop: the defaults are used
        }
        _settings.value = AppSettings(dark = dark, accentIndex = accent, language = lang)
    }

    fun setDark(dark: Boolean) {
        update { it.copy(dark = dark) }
    }

    fun setAccentIndex(index: Int) {
        update { it.copy(accentIndex = index.coerceIn(DEFAULT_ACCENTS.indices)) }
    }

    fun setLanguage(language: AppLanguage) {
        update { it.copy(language = language) }
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        save(next)
    }

    private fun save(value: AppSettings) {
        try {
            file.parentFile?.mkdirs()
            val p = Properties()
            p.setProperty("dark", value.dark.toString())
            p.setProperty("accent", value.accentIndex.toString())
            p.setProperty("lang", value.language.code)
            file.outputStream().use { p.store(it, "One Touch Controller - window settings") }
        } catch (e: Exception) {
            // not saved: the choice still works until the program is closed
        }
    }
}
