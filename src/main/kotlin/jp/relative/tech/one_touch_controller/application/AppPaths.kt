package jp.relative.tech.one_touch_controller.application

import java.io.File

/**
 * Single source of truth for all file paths used by the application.
 * Default locations are 100% compatible with existing installations; tests can pass a temporary [userHome].
 */
data class AppPaths(
    val userHome: File = File(System.getProperty("user.home"))
) {
    val dataDir: File = File(userHome, ".onetouch")
    val tokenFile: File = File(userHome, ".onetouch_token")
    val settingsFile: File = File(dataDir, "settings.properties")
    val routinesFile: File = File(dataDir, "routines.txt")
    val recordingsDir: File = File(dataDir, "recordings")
    val guardPropertiesFile: File = File(dataDir, "guard.properties")
    val cloudflaredFile: File = File(dataDir, "cloudflared.exe")
    val screenshotsDir: File = File(userHome, "Pictures")
}
