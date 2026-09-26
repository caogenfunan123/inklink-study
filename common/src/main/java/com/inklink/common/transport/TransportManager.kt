package com.inklink.common.transport

import com.inklink.common.protocol.InkMessage
import com.inklink.common.protocol.MessageCodec

/**
 * 传输模式管理器。
 *
 * 统一管理局域网/公网两种传输实例的切换。切换时先销毁旧实例（关闭 WS、置空 listener），
 * 再创建新实例，避免双连接同时收发造成消息乱序。
 *
 * 切换期间待发送的消息进入 [pending]，连接建立后统一重放，保证不丢。
 */
class TransportManager(
    private val deviceId: String,
    private val localRole: LocalWsTransport.LocalRole
) {

    companion object {
        private const val TAG = "TransportManager"
    }

    private var current: IMessageTransport? = null
    private var currentMode: TransportMode? = null
    var listener: TransportListener? = null
        private set

    private val pending = ArrayDeque<InkMessage>()

    /** 心跳间隔提供器，透传给底层传输，用于亮屏/灭屏自适应。 */
    var heartbeatIntervalProvider: (() -> Long)? = null

    /** 默认目标设备 ID。公网中转模式下自动填充到未指定 target 的消息上。 */
    var defaultTargetDeviceId: String? = null

    fun switchMode(
        mode: TransportMode,
        relayServerUrl: String? = null,
        localHost: String? = null,
        localPort: Int = LocalWsTransport.DEFAULT_PORT,
        trustAllCerts: Boolean = false,
        ablyKey: String? = null,
        ablyChannel: String? = null
    ) {
        val sameTarget = mode == currentMode && when (mode) {
            TransportMode.LOCAL -> (current as? LocalWsTransport)?.host == localHost &&
                (current as? LocalWsTransport)?.port == localPort
            TransportMode.RELAY -> (current as? ServerRelayTransport)?.serverUrl == relayServerUrl
            TransportMode.ABLY ->
                current is AblyRelayTransport &&
                (current as? AblyRelayTransport)?.channelName == ablyChannel
        }
        if (sameTarget) return
        current?.disconnect()
        current = null
        currentMode = null

        val next = when (mode) {
            TransportMode.LOCAL -> LocalWsTransport(
                role = localRole,
                host = localHost,
                port = localPort
            ).also { it.heartbeatIntervalProvider = heartbeatIntervalProvider }
            TransportMode.RELAY -> ServerRelayTransport(
                serverUrl = relayServerUrl
                    ?: throw IllegalArgumentException("RELAY 模式必须提供服务器地址"),
                deviceId = deviceId,
                trustAllCerts = trustAllCerts
            ).also { it.heartbeatIntervalProvider = heartbeatIntervalProvider }
            TransportMode.ABLY -> AblyRelayTransport(
                ablyKey = ablyKey
                    ?: throw IllegalArgumentException("ABLY 模式必须提供 Ably Key"),
                deviceId = deviceId,
                channelName = ablyChannel ?: AblyRelayTransport.DEFAULT_CHANNEL
            ).also { it.heartbeatIntervalProvider = heartbeatIntervalProvider }
        }
        next.setListener(object : TransportListener {
            override fun onTextMessage(message: InkMessage) {
                listener?.onTextMessage(message)
            }

            override fun onAudioMessage(frame: ByteArray) {
                listener?.onAudioMessage(frame)
            }

            override fun onConnectionChanged(connected: Boolean) {
                if (connected) flushPending()
                listener?.onConnectionChanged(connected)
            }
        })
        current = next
        currentMode = mode
        next.connect()
    }

    fun connect() = current?.connect()

    fun disconnect() {
        current?.disconnect()
        current = null
        currentMode = null
    }

    fun setListener(listener: TransportListener?) {
        this.listener = listener
    }

    fun isConnected(): Boolean = current?.isConnected() == true

    fun currentMode(): TransportMode? = currentMode

    /** 当前传输实例访问器（用于配置 Ably 好友 Presence 监听等底层能力）。 */
    fun currentTransport(): IMessageTransport? = current

    fun sendMessage(message: InkMessage) {
        val framed = if (message.targetDeviceId == null && defaultTargetDeviceId != null) {
            message.copy(targetDeviceId = defaultTargetDeviceId)
        } else {
            message
        }
        // Ably 单条消息硬上限 64KB，超限直接丢弃防止发送异常（含 audio/base64 场景）
        val encodedSize = MessageCodec.encodeText(framed).toByteArray(Charsets.UTF_8).size
        if (encodedSize > InkMessage.MAX_MESSAGE_BYTES) {
            android.util.Log.w(TAG, "消息超限丢弃: type=${framed.messageType} size=$encodedSize > ${InkMessage.MAX_MESSAGE_BYTES}")
            return
        }
        if (current?.isConnected() == true) {
            current?.sendMessage(framed)
        } else {
            synchronized(pending) { pending.addLast(framed) }
        }
    }

    fun sendAudio(frame: ByteArray) {
        if (current?.isConnected() == true) {
            current?.sendAudio(frame)
        }
        // 语音实时性优先，未连接时不缓存
    }

    private fun flushPending() {
        synchronized(pending) {
            while (pending.isNotEmpty()) {
                current?.sendMessage(pending.removeFirst())
            }
        }
    }
}
