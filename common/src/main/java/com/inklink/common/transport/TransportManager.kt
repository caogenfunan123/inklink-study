package com.inklink.common.transport

import com.inklink.common.protocol.InkMessage
import com.inklink.common.protocol.MessageCodec
import java.util.concurrent.CopyOnWriteArrayList

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

        /** pending 上限：长时间无网后重连一次性倾倒积压会击穿 Ably 限速/本地背压，FIFO 淘汰。 */
        private const val MAX_PENDING = 200
    }

    private var current: IMessageTransport? = null
    private var currentMode: TransportMode? = null
    var listener: TransportListener? = null
        private set

    /**
     * 页面级多播监听器。旧实现是页面 setListener 包裹全局 listener、退出时还原：
     * 两个页面同时注册时后一个页面退出会把前一个的包裹一起还原掉，链路说断就断。
     * 多播后页面只 add/remove 自己的一份，互不影响。
     */
    private val pageListeners = CopyOnWriteArrayList<TransportListener>()

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
                (current as? AblyRelayTransport)?.channelName == ablyChannel &&
                // Key 变化（如用户在弹窗补填初始为空的 Key）必须重建实例，
                // 否则旧实例 connect() 直接 return，永远连不上
                (current as? AblyRelayTransport)?.ablyKey == ablyKey
        }
        if (sameTarget) {
            // 复用已有连接：同步刷新心跳间隔提供器（亮/灭屏切换后 provider 引用可能已变，
            // 不刷新会一直沿用旧倍率，与灭屏拉长间隔的设计相悖）
            current?.heartbeatIntervalProvider = heartbeatIntervalProvider
            return
        }
        // 切模式只断旧连接：pending 由本类持有，跨模式保留积压待发消息
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
                pageListeners.forEach { runCatching { it.onTextMessage(message) } }
            }

            override fun onAudioMessage(frame: ByteArray) {
                listener?.onAudioMessage(frame)
                pageListeners.forEach { runCatching { it.onAudioMessage(frame) } }
            }

            override fun onConnectionChanged(connected: Boolean) {
                if (connected) flushPending()
                listener?.onConnectionChanged(connected)
                pageListeners.forEach { runCatching { it.onConnectionChanged(connected) } }
            }
        })
        current = next
        currentMode = mode
        next.connect()
    }

    fun connect() = current?.connect()

    /**
     * 主动断开。
     *
     * @param clearPending 是否丢弃积压消息。用户显式「断开」应清空（默认 true）——
     *   否则今晨未送达的聊天/围栏指令会在数小时后切模式重连时原样重放，家长以为
     *   孩子当时已收到；[switchMode] 切换期间不清（内部只断旧连接，pending 由
     *   本类持有，自动跨模式保留）。
     */
    fun disconnect(clearPending: Boolean = true) {
        current?.disconnect()
        current = null
        currentMode = null
        if (clearPending) {
            synchronized(pending) { pending.clear() }
        }
    }

    fun setListener(listener: TransportListener?) {
        this.listener = listener
    }

    /** 注册页面级监听（onDestroy 里 [removeListener] 成对移除）。 */
    fun addListener(l: TransportListener) {
        pageListeners.addIfAbsent(l)
    }

    fun removeListener(l: TransportListener) {
        pageListeners.remove(l)
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
            // isConnected() 与底层发送之间存在断窗：连接可能在判定后瞬间断开，
            // 直接发送会静默丢失（与「pending 保证不丢」的类契约相悖）。
            // 发送异常一律回落到 pending，而不是把消息丢出进程。
            val sent = runCatching { current?.sendMessage(framed) }
            if (sent.isFailure) enqueuePending(framed)
        } else {
            enqueuePending(framed)
        }
    }

    private fun enqueuePending(message: InkMessage) {
        synchronized(pending) {
            if (pending.size >= MAX_PENDING) {
                val dropped = pending.removeFirst()
                android.util.Log.w(TAG, "pending 已满，丢弃最旧消息: type=${dropped.messageType}")
            }
            pending.addLast(message)
        }
    }

    fun sendAudio(frame: ByteArray) {
        if (current?.isConnected() == true) {
            current?.sendAudio(frame)
        }
        // 语音实时性优先，未连接时不缓存
    }

    private fun flushPending() {
        // 锁内只做快照，锁外发送：持锁调用底层 send 会把阻塞传播给所有入队方
        val snapshot = synchronized(pending) {
            if (pending.isEmpty()) return
            pending.toList().also { pending.clear() }
        }
        snapshot.forEach { msg ->
            if (current?.isConnected() == true) {
                runCatching { current?.sendMessage(msg) }
            } else {
                enqueuePending(msg)
            }
        }
    }
}
