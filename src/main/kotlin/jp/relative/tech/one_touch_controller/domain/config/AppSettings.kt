package jp.relative.tech.one_touch_controller.domain.config

const val DEFAULT_SERVER_PORT = 8080            // HTTP port for commands (the app uses 8080)
const val DEFAULT_DISCOVERY_PORT = 8888         // UDP port for auto-discovery
const val MIN_REMOTE_TOKEN_LENGTH = 16          // Internet access is refused unless the token is at least this long
const val DEFAULT_SERVER_TOKEN = "change-me-123"

enum class AppLanguage(val code: String) {
    EN("en"),
    JA("ja"),
    ZH("zh");

    companion object {
        fun fromCode(code: String?, fallback: AppLanguage = EN): AppLanguage =
            entries.firstOrNull { it.code == code } ?: fallback
    }
}

/** One accent colour (pure RGB values so both Compose UI and Swing overlays can use it). */
data class AccentSpec(
    val onDarkRgb: Int,
    val onLightRgb: Int,
    val inkOnDarkRgb: Int
)

val DEFAULT_ACCENTS: List<AccentSpec> = listOf(
    AccentSpec(0x3DD6C6, 0x0E9488, 0x052521),   // teal
    AccentSpec(0x6AA8FF, 0x2563EB, 0x06183A),   // blue
    AccentSpec(0xA78BFA, 0x7C3AED, 0x1C0F45),   // violet
    AccentSpec(0xFFA24C, 0xD9650B, 0x3A1C00),   // orange
    AccentSpec(0xFF7EB6, 0xDB2777, 0x3D0A22)    // pink
)

/** Window appearance settings: light or dark theme, accent colour index, and language. */
data class AppSettings(
    val dark: Boolean = true,
    val accentIndex: Int = 0,
    val language: AppLanguage = AppLanguage.EN
) {
    fun currentAccent(): AccentSpec = DEFAULT_ACCENTS[accentIndex.coerceIn(DEFAULT_ACCENTS.indices)]

    fun accentRgb(): Int = currentAccent().let { if (dark) it.onDarkRgb else it.onLightRgb }
}
