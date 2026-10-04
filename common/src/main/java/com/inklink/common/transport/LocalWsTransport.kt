package com.inklink.common.transport

import com.inklink.common.protocol.InkMessage
import com.inklink.common.protocol.MessageCodec
import com.inklink.common.protocol.MessageType
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 局域网 WebSocket 传输实现。
 *
 * - [LocalRole.SERVER]：app-host 侧，监听指定端口等待主控端连接。
 * - [LocalRole.CLIENT]：app-controller 侧，主动连接受控端 IP:port。
 */
class LocalWsTransport(
    private val role: LocalRole,
    val host: String? = null,
    val port: Int = DEFAULT_PORT
) : IMessageTransport {

    private var server: LocalServer? = null
    private var client: LocalClient? = null

    @Volatile
    private var listener: TransportListener? = null

    private val connections = LinkedHashSet<WebSocket>()

    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "local-heartbeat").apply { isDaemon = true }
    }
    private var heartbeatTask: ScheduledFuture<*>? = null
    private val heartbeatEnabled = AtomicBoolean(false)

    /** 心跳间隔提供器，返回毫秒。为空时使用 [HEARTBEAT_INTERVAL_MS]。 */
    var heartbeatIntervalProvider: (() -> Long)? = null

    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "local-reconnect").apply { isDaemon = true }
    }
    private val clientReconnectEnabled = AtomicBoolean(false)
    private val reconnecting = AtomicBoolean(false)
    private val backoffAttempt = AtomicInteger(0)

    override fun connect() {
        when (role) {
            LocalRole.SERVER -> {
                if (server != null) return
                server = LocalServer(InetSocketAddress(port)).also { it.start() }
            }
            LocalRole.CLIENT -> {
                if (client != null) return
                clientReconnectEnabled.set(true)
                backoffAttempt.set(0)
                openClient()
            }
        }
        startHeartbeat()
    }

    override fun disconnect() {
        stopHeartbeat()
        clientReconnectEnabled.set(false)
        server?.let { runCatching { it.stop(2000) } }
        server = null
        client?.let { runCatching { it.closeBlocking() } }
        client = null
        synchronized(connections) { connections.clear() }
        // 停掉心跳/重连线程：TransportManager 切换模式会新建实例，旧实例的线程不回收会累积泄漏
        heartbeatExecutor.shutdownNow()
        reconnectExecutor.shutdownNow()
        notifyConnectionChanged()
    }

    override fun isConnected(): Boolean = when (role) {
        LocalRole.SERVER -> synchronized(connections) { connections.any { it.isOpen } }
        LocalRole.CLIENT -> client?.isOpen == true
    }

    override fun sendMessage(message: InkMessage) {
        val json = MessageCodec.encodeText(message)
        when (role) {
            LocalRole.SERVER -> broadcast(json)
            LocalRole.CLIENT -> client?.send(json)
        }
    }

    override fun sendAudio(frame: ByteArray) {
        when (role) {
            LocalRole.SERVER -> broadcast(frame)
            LocalRole.CLIENT -> client?.send(frame)
        }
    }

    override fun setListener(listener: TransportListener?) {
        this.listener = listener
    }

    private fun broadcast(payload: String) {
        synchronized(connections) { connections.filter { it.isOpen } }
            .forEach { it.send(payload) }
    }

    private fun broadcast(payload: ByteArray) {
        synchronized(connections) { connections.filter { it.isOpen } }
            .forEach { it.send(payload) }
    }

    private fun notifyConnectionChanged() {
        listener?.onConnectionChanged(isConnected())
    }

    private fun dispatchText(json: String) {
        MessageCodec.decodeTextOrNull(json)?.let { listener?.onTextMessage(it) }
    }

    private fun startHeartbeat() {
        heartbeatEnabled.set(true)
        scheduleNextHeartbeat()
    }

    private fun scheduleNextHeartbeat() {
        if (!heartbeatEnabled.get()) return
        if (heartbeatTask != null && !heartbeatTask!!.isDone) return
        val delay = heartbeatIntervalProvider?.invoke() ?: HEARTBEAT_INTERVAL_MS
        heartbeatTask = heartbeatExecutor.schedule({
            runCatching {
                if (isConnected()) {
                    sendMessage(InkMessage.control(MessageType.HEARTBEAT))
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

    private fun openClient() {
        val addr = host ?: return
        client = LocalClient("ws://$addr:$port").also { it.connect() }
    }

    private fun scheduleReconnect() {
        if (!clientReconnectEnabled.get()) return
        if (!reconnecting.compareAndSet(false, true)) return
        val attempt = backoffAttempt.getAndIncrement()
        val delay = minOf(1_000L shl attempt.coerceAtMost(5), MAX_RECONNECT_MS)
        reconnectExecutor.schedule({
            reconnecting.set(false)
            if (!clientReconnectEnabled.get()) return@schedule
            openClient()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private inner class LocalServer(address: InetSocketAddress) : WebSocketServer(address) {

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            synchronized(connections) { connections.add(conn) }
            notifyConnectionChanged()
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            synchronized(connections) { connections.remove(conn) }
            notifyConnectionChanged()
        }

        override fun onMessage(conn: WebSocket, message: String) = dispatchText(message)

        override fun onMessage(conn: WebSocket, message: java.nio.ByteBuffer) {
            val frame = ByteArray(message.remaining())
            message.get(frame)
            listener?.onAudioMessage(frame)
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            ex.printStackTrace()
        }

        override fun onStart() = Unit
    }

    private inner class LocalClient(url: String) : org.java_websocket.client.WebSocketClient(
        java.net.URI.create(url)
    ) {
        override fun onOpen(handshake: org.java_websocket.handshake.ServerHandshake) {
            backoffAttempt.set(0)
            notifyConnectionChanged()
            // 连接后立即发送心跳，对齐保活与连接状态
            sendMessage(InkMessage.control(MessageType.HEARTBEAT))
        }

        override fun onClose(code: Int, reason: String, remote: Boolean) {
            notifyConnectionChanged()
            scheduleReconnect()
        }

        override fun onMessage(message: String) = dispatchText(message)

        override fun onMessage(bytes: java.nio.ByteBuffer) {
            val frame = ByteArray(bytes.remaining())
            bytes.get(frame)
            listener?.onAudioMessage(frame)
        }

        override fun onError(ex: Exception) {
            ex.printStackTrace()
        }
    }

    enum class LocalRole { SERVER, CLIENT }

    companion object {
        const val DEFAULT_PORT = 8080
        const val HEARTBEAT_INTERVAL_MS = 15_000L
        const val MAX_RECONNECT_MS = 30_000L
    }
}
