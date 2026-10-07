package com.inklink.common.service.discovery

import com.google.gson.Gson
import com.inklink.common.utils.MonoClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网设备发现结果。
 */
data class DiscoveredDevice(
    val deviceId: String,
    val ip: String,
    val wsPort: Int
)

/**
 * 基于 UDP 广播的局域网设备发现。
 *
 * - 受控端调用 [startResponder] 监听广播，收到发现请求后回应用户自身信息。
 * - 主控端调用 [scan] 发送广播并收集固定时间内的响应。
 *
 * 报文约定（UTF-8 文本）：
 * - 请求：`INKLINK_DISCOVER`
 * - 应答：`INKLINK_REPLY:` + JSON（含 deviceId / wsPort）
 */
object UdpDiscovery {

    const val DISCOVERY_PORT = 9000

    private const val REQUEST = "INKLINK_DISCOVER"
    private const val REPLY_PREFIX = "INKLINK_REPLY:"

    private val gson = Gson()

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    /** 受控端启动应答线程。绑定失败（端口占用等）时复位状态，允许后续重试。 */
    fun startResponder(deviceId: String, wsPort: Int) {
        if (running.get()) return
        running.set(true)
        thread = Thread({ responderLoop(deviceId, wsPort) }, "udp-discovery").apply {
            isDaemon = true
            start()
        }
    }

    fun stopResponder() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        thread = null
    }

    /** 主控端扫描局域网设备，回调在扫描线程触发。 */
    fun scan(timeoutMs: Long = 2000, onDevice: (DiscoveredDevice) -> Unit, onDone: () -> Unit) {
        Thread({
            runCatching {
                val sock = DatagramSocket().apply { broadcast = true }
                val buf = ByteArray(1024)
                val request = REQUEST.toByteArray(Charsets.UTF_8)
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                sock.send(DatagramPacket(request, request.size, broadcastAddr, DISCOVERY_PORT))

                // 扫描窗口用单调钟：socket soTimeout 与墙钟混用，墙钟回拨会让
                // 等待值变负、扫描线程行为不可预期
                val deadline = MonoClock.now() + timeoutMs
                while (true) {
                    val wait = (deadline - MonoClock.now()).toInt()
                    if (wait <= 0) break
                    sock.soTimeout = wait
                    val packet = DatagramPacket(buf, buf.size)
                    runCatching {
                        sock.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        if (text.startsWith(REPLY_PREFIX)) {
                            parseReply(text.removePrefix(REPLY_PREFIX), packet.address?.hostAddress)
                                ?.let(onDevice)
                        }
                    }
                }
                sock.close()
            }
            onDone()
        }, "udp-scan").apply { isDaemon = true; start() }
    }

    private fun responderLoop(deviceId: String, wsPort: Int) {
        var bound = false
        try {
            val sock = DatagramSocket(DISCOVERY_PORT)
            socket = sock
            bound = true
            val buf = ByteArray(256)
            while (running.get()) {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (text == REQUEST) {
                    val reply = REPLY_PREFIX + gson.toJson(DiscoveryReply(deviceId, wsPort))
                    val bytes = reply.toByteArray(Charsets.UTF_8)
                    sock.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
                }
            }
        } catch (e: Exception) {
            // 绑定失败（端口被占用）或运行中异常：必须复位 running，
            // 否则 startResponder 的守卫会永久短路，局域网发现静默失效且无任何日志
            android.util.Log.w("UdpDiscovery", "responder 退出: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            socket = null
            if (!bound) running.set(false)
        }
    }

    private fun parseReply(json: String, ip: String?): DiscoveredDevice? {
        if (ip.isNullOrBlank()) return null
        return runCatching {
            val reply = gson.fromJson(json, DiscoveryReply::class.java)
            DiscoveredDevice(reply.deviceId, ip, reply.wsPort)
        }.getOrNull()
    }

    private data class DiscoveryReply(
        val deviceId: String,
        val wsPort: Int
    )
}
