package jp.relative.tech.one_touch_controller.server

import jp.relative.tech.one_touch_controller.domain.config.AppLogger
import jp.relative.tech.one_touch_controller.domain.config.DEFAULT_DISCOVERY_PORT
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * UDP auto-discovery listener: the phone broadcasts "PCREMOTE_DISCOVER" and this PC replies "PCREMOTE_HERE:<pc name>".
 */
class DiscoveryService(
    private val port: Int = DEFAULT_DISCOVERY_PORT,
    private val logger: AppLogger
) {
    @Volatile private var discoverySocket: DatagramSocket? = null

    fun start() {
        try {
            val socket = DatagramSocket(port)
            discoverySocket = socket
            thread(isDaemon = true, name = "udp-discovery") {
                val buffer = ByteArray(256)
                try {
                    while (!socket.isClosed) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        if (text == "PCREMOTE_DISCOVER") {
                            val name = InetAddress.getLocalHost().hostName
                            val reply = "PCREMOTE_HERE:$name".toByteArray()
                            socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                            logger.log("Find PC request answered (${packet.address.hostAddress})")
                        }
                    }
                } catch (e: Exception) {
                    // the socket was closed because the server was stopped: this is normal
                }
            }
        } catch (e: Exception) {
            logger.log("Warning: auto-discovery is unavailable (${e.message})")
        }
    }

    fun stop() {
        discoverySocket?.close()
        discoverySocket = null
    }
}
