package jp.relative.tech.one_touch_controller.server

import java.net.Inet4Address
import java.net.NetworkInterface

// Reads a value from a simple JSON body, e.g. jsonField("{\"dx\":5}", "dx") -> "5"
fun jsonField(body: String, key: String): String? =
    Regex("\"$key\"\\s*:\\s*\"?([^\",}\\s]+)\"?").find(body)?.groupValues?.get(1)

// Finds this PC's local network addresses (the ones you type into the phone app)
fun localAddresses(): List<String> {
    val result = mutableListOf<String>()
    val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return result
    for (ni in interfaces) {
        if (!ni.isUp || ni.isLoopback) continue
        for (addr in ni.inetAddresses.toList()) {
            if (addr is Inet4Address && addr.isSiteLocalAddress) {
                result.add("${addr.hostAddress}   (${ni.displayName})")
            }
        }
    }
    return result
}
