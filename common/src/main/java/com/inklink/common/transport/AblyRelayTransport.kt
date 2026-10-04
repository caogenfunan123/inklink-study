package com.inklink.common.transport

import com.inklink.common.protocol.InkMessage
import com.inklink.common.protocol.MessageCodec
import com.inklink.common.protocol.MessageType
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.ConnectionEvent
import io.ably.lib.realtime.ConnectionState
import io.ably.lib.realtime.Presence
import io.ably.lib.types.ClientOptions
import io.ably.lib.types.Message
import io.ably.lib.types.PresenceMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ably 公网中转传输实现（临时原型方案，4G-4G 中转）。
 *
 * 基于 Ably Pub/Sub 广播频道，双端共用同一频道。频道是广播语义，所有订阅者都会
 * 收到消息，因此在本层按 [InkMessage.targetDeviceId] 过滤：只处理「发给本机」或
 * 「广播（target 为空）」的消息，模拟点对点。
 *
 * 好友社交扩展（阶段六，仅 Ably 模式）：
 * - [attachChannel] 挂载好友频道（对称订阅），进站消息仅放行 [FriendPolicy.ALLOWED_TYPES] 白名单；
 * - [targetChannelResolver] 按目标设备路由发布频道：发好友走好友频道，其余走主频道；
 * - Presence 回调上报好友上下线（主频道不参与 Presence）。
 *
 * 语音二进制帧本期搁置，Ably 只承载文本 JSON 消息。
 */
class AblyRelayTransport(
    private val ablyKey: String,
    private val deviceId: String,
    val channelName: String = DEFAULT_CHANNEL
) : IMessageTransport {

    @Volatile
    private var listener: TransportListener? = null

    private var realtime: AblyRealtime? = null
    private var channel: Channel? = null

    /** 好友频道表：频道名 -> Channel 实例 */
    private val extraChannels = ConcurrentHashMap<String, Channel>()

    /** 发送路由：targetDeviceId -> 频道名；返回 null 或未知目标走主频道 */
    @Volatile
    var targetChannelResolver: ((targetDeviceId: String?) -> String?)? = null

    /** 好友 Presence 状态回调：好友频道 clientId 上下线变化 */
    @Volatile
    var presenceListener: ((channelName: String, clientId: String, online: Boolean) -> Unit)? = null

    private val heartbeatExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ably-heartbeat").apply { isDaemon = true }
        }

    private var heartbeatTask: ScheduledFuture<*>? = null
    private val heartbeatEnabled = AtomicBoolean(false)

    /** 心跳间隔提供器，返回毫秒。为空时使用 [HEARTBEAT_INTERVAL_MS]。 */
    override var heartbeatIntervalProvider: (() -> Long)? = null

    override fun connect() {
        if (realtime != null) return
        if (ablyKey.isBlank()) {
            listener?.onConnectionChanged(false)
            return
        }
        runCatching {
            val opts = ClientOptions(ablyKey).apply {
                clientId = deviceId
                // 关闭消息回显：发布者不接收自己发布的消息，避免广播模式下误处理与流量翻倍
                echoMessages = false
            }
            val rt = AblyRealtime(opts)
            realtime = rt

            rt.connection.on(ConnectionEvent.connected) {
                listener?.onConnectionChanged(true)
                startHeartbeat()
            }
            rt.connection.on(ConnectionEvent.disconnected) { listener?.onConnectionChanged(false) }
            rt.connection.on(ConnectionEvent.suspended) { listener?.onConnectionChanged(false) }
            rt.connection.on(ConnectionEvent.failed) { listener?.onConnectionChanged(false) }

            channel = rt.channels.get(channelName)
            channel?.subscribe { message -> onMessage(message, channelName) }
        }.onFailure {
            realtime = null
            channel = null
            listener?.onConnectionChanged(false)
        }
    }

    override fun disconnect() {
        stopHeartbeat()
        runCatching { channel?.unsubscribe() }
        channel = null
        extraChannels.values.forEach { ch ->
            runCatching { ch.presence.leave(null, null) }
            runCatching { ch.unsubscribe() }
        }
        extraChannels.clear()
        runCatching { realtime?.close() }
        realtime = null
        // 停掉心跳线程，避免 TransportManager 切换模式后旧实例线程常驻
        heartbeatExecutor.shutdownNow()
        listener?.onConnectionChanged(false)
    }

    override fun isConnected(): Boolean =
        realtime?.connection?.state == ConnectionState.connected

    /**
     * 挂载好友频道：订阅消息（白名单过滤）与 Presence，并进入 Presence。
     * 须在 connect() 之后调用；连接未就绪时 Ably SDK 会自动排队至连接建立。
     */
    fun attachChannel(name: String) {
        if (name == channelName || extraChannels.containsKey(name)) return
        val rt = realtime ?: return
        runCatching {
            val ch = rt.channels.get(name)
            extraChannels[name] = ch
            ch.subscribe { message -> onMessage(message, name) }
            ch.presence.subscribe(object : Presence.PresenceListener {
                override fun onPresenceMessage(pm: PresenceMessage) {
                    onPresence(name, pm)
                }
            })
            ch.presence.enter(null, null)
            // 拉取当前在场成员，触发存量好友上线回调
            ch.presence.get()?.forEach { pm ->
                if (pm.clientId != null && pm.clientId != deviceId) {
                    presenceListener?.invoke(name, pm.clientId, true)
                }
            }
        }.onFailure { extraChannels.remove(name) }
    }

    /** 卸载好友频道：离开 Presence 并取消订阅。 */
    fun detachChannel(name: String) {
        runCatching {
            extraChannels.remove(name)?.let { ch ->
                ch.presence.leave(null, null)
                ch.unsubscribe()
            }
        }
    }

    override fun sendMessage(message: InkMessage) {
        val framed = message.copy(fromDeviceId = message.fromDeviceId ?: deviceId)
        val json = MessageCodec.encodeText(framed)
        val ablyMsg = Message(EVENT_NAME, json).apply {
            id = framed.msgId // 开启 Ably 消息服务端幂等去重
        }
        val outChannel = if (framed.targetDeviceId.isNullOrBlank()) {
            channel
        } else {
            targetChannelResolver?.invoke(framed.targetDeviceId)?.let { extraChannels[it] } ?: channel
        }
        outChannel?.publish(ablyMsg)
    }

    override fun sendAudio(frame: ByteArray) {
        // 语音本期搁置：Ably 只传文本 JSON 消息，二进制语音帧不使用
    }

    override fun setListener(listener: TransportListener?) {
        this.listener = listener
    }

    private fun startHeartbeat() {
        heartbeatEnabled.set(true)
        scheduleNextHeartbeat()
    }

    private fun scheduleNextHeartbeat() {
        if (!heartbeatEnabled.get()) return
        if (heartbeatTask != null && heartbeatTask!!.isDone.not()) return
        val delay = heartbeatIntervalProvider?.invoke() ?: HEARTBEAT_INTERVAL_MS
        heartbeatTask = heartbeatExecutor.schedule({
            runCatching {
                if (isConnected()) {
                    channel?.publish(
                        EVENT_NAME,
                        MessageCodec.encodeText(InkMessage.control(MessageType.HEARTBEAT, from = deviceId))
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

    /**
     * 进站消息处理：主频道全量放行（按 target 过滤）；
     * 好友频道仅放行 [FriendPolicy.ALLOWED_TYPES] 白名单 —— 只游戏、不控制。
     */
    private fun onMessage(message: Message, fromChannel: String) {
        val json = message.data as? String ?: return
        val ink = MessageCodec.decodeTextOrNull(json) ?: return
        val target = ink.targetDeviceId
        if (target != null && target != deviceId) return
        if (fromChannel != channelName && ink.type !in FriendPolicy.ALLOWED_TYPES) return
        listener?.onTextMessage(ink)
    }

    private fun onPresence(name: String, pm: PresenceMessage) {
        val clientId = pm.clientId ?: return
        if (clientId == deviceId) return
        val online = when (pm.action) {
            PresenceMessage.Action.leave, PresenceMessage.Action.absent -> false
            else -> true
        }
        presenceListener?.invoke(name, clientId, online)
    }

    companion object {
        const val DEFAULT_CHANNEL = "inklink-proto-ch01"
        const val CHANNEL_PREFIX = "inklink-pet-"
        const val EVENT_NAME = "ink-message"
        const val HEARTBEAT_INTERVAL_MS = 15_000L
    }
}

/**
 * 好友频道进站消息白名单：受控端互玩只开放游戏与串门互动。
 * 管理类指令（投喂/重置PIN/赠礼/任务）与状态隐私报文（30/35/40）对好友一律关闭。
 */
object FriendPolicy {
    val ALLOWED_TYPES: Set<Int> = setOf(
        MessageType.PET_GAME_INVITE.code,
        MessageType.PET_GAME_ACTION.code,
        MessageType.PET_BAG_INTERACT.code
    )
}
