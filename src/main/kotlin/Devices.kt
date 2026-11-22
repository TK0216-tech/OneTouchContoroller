// Connected devices: which phones (or browsers) are using this PC right now.
// Shown in the PC window (Home tab), where a device can be disconnected.

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One device. [id] is its address (for internet visitors: the address Cloudflare reports). */
data class DeviceInfo(
    val id: String,
    val app: String,            // "Android app", "Web (iPhone)", ...
    val viaInternet: Boolean,
    val firstSeen: Long,
    val lastSeen: Long,
    val live: Boolean           // has a live connection open right now (touchpad / screen)
)

/** Works out which app is talking to us from the "User-Agent" the phone sends. */
fun appFromUserAgent(ua: String?): String {
    val u = (ua ?: "").lowercase()
    return when {
        u.startsWith("okhttp") -> "Android app"
        u.startsWith("dalvik") -> "Android app (Gesture Operate)"
        u.contains("iphone") -> "Web (iPhone)"
        u.contains("ipad") -> "Web (iPad)"
        u.contains("android") -> "Web (Android)"
        u.contains("windows") -> "Web (Windows)"
        u.contains("macintosh") -> "Web (Mac)"
        u.isBlank() -> "Unknown app"
        else -> "Web browser"
    }
}

object Devices {
    private const val FORGET_MS = 10 * 60 * 1000L       // a device not seen for 10 minutes leaves the list

    private val map = ConcurrentHashMap<String, DeviceInfo>()
    private val liveCount = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile private var lastPublish = 0L

    /** What the PC window shows (it redraws by itself when this changes). */
    var list by mutableStateOf<List<DeviceInfo>>(emptyList())
        private set

    /** Called after every request with a correct token. Returns true if the device is new (= "connected"). */
    fun seen(id: String, userAgent: String?, viaInternet: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        val before = map[id]
        val app = appFromUserAgent(userAgent)
        map[id] = DeviceInfo(id, if (before != null && app == "Unknown app") before.app else app, viaInternet,
            before?.firstSeen ?: now, now, (liveCount[id]?.get() ?: 0) > 0)
        // the list on screen does not need every mouse movement: at most every 2 seconds, or at once for a new device
        if (before == null || now - lastPublish > 2000) publish(now)
        return before == null
    }

    fun liveOpened(id: String, userAgent: String?, viaInternet: Boolean): Boolean {
        liveCount.getOrPut(id) { AtomicInteger() }.incrementAndGet()
        return seen(id, userAgent, viaInternet)
    }

    fun liveClosed(id: String) {
        liveCount[id]?.decrementAndGet()
        map[id]?.let { map[id] = it.copy(live = (liveCount[id]?.get() ?: 0) > 0) }
        publish(System.currentTimeMillis())
    }

    /** Disconnects a device: it is refused for [minutes] minutes, and its live connection is closed. */
    fun disconnect(id: String, minutes: Int = 10) {
        Guard.blockFor(id, minutes * 60 * 1000L)
        map.remove(id)
        publish(System.currentTimeMillis())
    }

    fun publish(now: Long = System.currentTimeMillis()) {
        lastPublish = now
        map.entries.removeIf { !it.value.live && now - it.value.lastSeen > FORGET_MS }
        list = map.values.sortedByDescending { it.lastSeen }
    }
}

/** "now", "12 s ago", "3 min ago" (for the PC window) */
fun agoText(ms: Long): String = when {
    ms < 5_000 -> tr("now")
    ms < 60_000 -> tr("%1 s ago", ms / 1000)
    else -> tr("%1 min ago", ms / 60_000)
}
