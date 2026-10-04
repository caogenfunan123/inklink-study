package com.inklink.common.transport

import com.inklink.common.protocol.InkMessage
import com.inklink.common.protocol.MessageCodec
import com.inklink.common.protocol.MessageType
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLParameters

/**
 * 公网中转传输实现（甲骨文 Ubuntu WS 中转服务）。
 *
 * 双端均主动连接服务器，服务器按 [InkMessage.fromDeviceId]/[InkMessage.targetDeviceId]
 * 做消息路由透传。连接后立即上报自身 deviceId 完成注册。
 *
 * 关键能力：
 * - 指数退避重连（1s→2s→4s→…→上限，带 jitter），避免服务器抖动时重连风暴。
 * - 可选关闭证书校验（仅自用测试，生产默认关闭该开关）。
 */
class ServerRelayTransport(
    val serverUrl: String,
    private val deviceId: String,
    private val trustAllCerts: Boolean = false,
    private val maxBackoffMillis: Long = 60_000L
) : IMessageTransport {

    private var client: RelayClient? = null

    @Volatile
    private var listener: TransportListener? = null

    private val reconnectExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "relay-reconnect").apply { isDaemon = true }
        }

    private val reconnecting = AtomicBoolean(false)
    private val backoffAttempt = AtomicInteger(0)

    private var heartbeatTask: ScheduledFuture<*>? = null
    private val heartbeatEnabled = AtomicBoolean(false)

    /** 心跳间隔提供器，返回毫秒。为空时使用 [HEARTBEAT_INTERVAL_MS]。 */
    override var heartbeatIntervalProvider: (() -> Long)? = null

    override fun connect() {
        reconnecting.set(false)
        backoffAttempt.set(0)
        open()
    }

    override fun disconnect() {
        reconnecting.set(false)
        stopHeartbeat()
        client?.let { runCatching { it.closeBlocking() } }
        client = null
        // 停掉重连调度线程，避免 TransportManager 切换模式后旧实例线程常驻
        reconnectExecutor.shutdownNow()
        listener?.onConnectionChanged(false)
    }

    override fun isConnected(): Boolean = client?.isOpen == true

    override fun sendMessage(message: InkMessage) {
        val framed = message.copy(fromDeviceId = message.fromDeviceId ?: deviceId)
        client?.send(MessageCodec.encodeText(framed))
    }

    override fun sendAudio(frame: ByteArray) {
        client?.send(frame)
    }

    override fun setListener(listener: TransportListener?) {
        this.listener = listener
    }

    private fun open() {
        client = RelayClient(URI.create(serverUrl))
        // connect() 非阻塞，内部自建连接线程
        client?.connect()
    }

    private fun scheduleReconnect() {
        if (!reconnecting.compareAndSet(false, true)) return
        val attempt = backoffAttempt.getAndIncrement()
        val base = minOf(1_000L shl attempt.coerceAtMost(10), maxBackoffMillis)
        val jitter = (Math.random() * 0.5 * base).toLong()
        val delay = base + jitter
        reconnectExecutor.schedule({
            reconnecting.set(false)
            open()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun scheduleHeartbeat() {
        heartbeatEnabled.set(true)
        heartbeatTask?.cancel(false)
        scheduleNextHeartbeat()
    }

    private fun scheduleNextHeartbeat() {
        if (!heartbeatEnabled.get()) return
        val delay = heartbeatIntervalProvider?.invoke() ?: HEARTBEAT_INTERVAL_MS
        heartbeatTask = reconnectExecutor.schedule({
            runCatching {
                if (isConnected()) {
                    client?.send(
                        MessageCodec.encodeText(
                            InkMessage.control(MessageType.HEARTBEAT, from = deviceId)
                        )
                    )
                }
            }
            heartbeatTask = null
            scheduleNextHeartbeat()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun stopHeartbeat() {
        heartbeatEnabled.set(false)
        heartbeatTask?.cancel(true)
        heartbeatTask = null
    }

    private inner class RelayClient(serverUri: URI) : WebSocketClient(serverUri) {

        init {
            if (trustAllCerts) {
                setSocketFactory(SslTrustAll.createSocketFactory())
            }
        }

        override fun onSetSSLParameters(sslParameters: SSLParameters) {
            // 仅自用测试：信任所有证书时同时跳过 hostname 验证，实现完全忽略校验
            if (!trustAllCerts) {
                super.onSetSSLParameters(sslParameters)
            }
        }

        override fun onOpen(handshake: ServerHandshake) {
            backoffAttempt.set(0)
            // 上报自身 deviceId，完成服务器映射注册
            send(MessageCodec.encodeText(InkMessage.control(MessageType.HEARTBEAT, from = deviceId)))
            listener?.onConnectionChanged(true)
            scheduleHeartbeat()
        }

        override fun onClose(code: Int, reason: String, remote: Boolean) {
            listener?.onConnectionChanged(false)
            if (reconnectExecutor.isShutdown) return
            scheduleReconnect()
        }

        override fun onMessage(message: String) {
            MessageCodec.decodeTextOrNull(message)?.let { listener?.onTextMessage(it) }
        }

        override fun onMessage(bytes: java.nio.ByteBuffer) {
            val frame = ByteArray(bytes.remaining())
            bytes.get(frame)
            listener?.onAudioMessage(frame)
        }

        override fun onError(ex: Exception) {
            ex.printStackTrace()
        }
    }

    companion object {
        const val HEARTBEAT_INTERVAL_MS = 15_000L
    }
}
