package com.inklink.host

import android.app.Application
import com.inklink.common.chat.ChatStore
import com.inklink.common.protocol.ChatMessage
import com.inklink.common.protocol.InkMessage
import com.inklink.common.protocol.MessageType
import com.inklink.common.transport.TransportManager
import com.inklink.common.transport.LocalWsTransport
import com.inklink.common.utils.DeviceIdProvider
import com.inklink.host.BuildConfig
import com.inklink.host.state.HostScreenState

/**
 * 受控端 Application。
 *
 * 初始化设备 ID、传输管理器与共享状态。受控端局域网模式扮演 WS Server，
 * 亦可配置公网中转服务器地址切换为 RELAY 模式（双端连服务器）。
 */
class InkHostApplication : Application() {

    lateinit var transportManager: TransportManager
        private set

    lateinit var deviceId: String
        private set

    val hostState = HostScreenState()
    val chatStore = ChatStore()

    /** 好友仓库（阶段六，仅 Ably 模式互玩）。 */
    val friendRepository by lazy { com.inklink.host.friend.FriendRepository(this) }

    /** 公网中转服务器地址；为空时使用局域网直连。 */
    var relayServerUrl: String?
        get() = prefs().getString(KEY_RELAY_URL, null)
        set(value) {
            prefs().edit().putString(KEY_RELAY_URL, value).apply()
        }

    /** 传输模式：local / relay / ably。默认 Ably 中转（4G-4G）。 */
    var transportMode: String
        get() = prefs().getString(KEY_TRANSPORT_MODE, MODE_ABLY) ?: MODE_ABLY
        set(value) {
            prefs().edit().putString(KEY_TRANSPORT_MODE, value).apply()
        }

    /**
     * Ably Root Key:抽屉里填写优先(存 prefs),否则回落 BuildConfig
     * (本地/CI 注入;公开源码构建时为空,必须由用户填写)。
     */
    var ablyKey: String
        get() = prefs().getString(KEY_ABLY_KEY, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.ABLY_KEY
        set(value) {
            prefs().edit().putString(KEY_ABLY_KEY, value).apply()
        }

    /**
     * 配对密钥：派生 Ably 私有频道名（inklink-pet-${key}）与远程重置 PIN 的 HMAC 密钥。
     * 受控端与主控端必须一致；默认为公开源码内置密钥，任何人可猜测频道名并伪造指令，
     * 应由两端协商改为私有值（宿主设置 → 配对密钥设置）。
     */
    var pairingKey: String
        get() = prefs().getString(KEY_PAIRING_KEY, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PAIRING_KEY
        set(value) {
            prefs().edit()
                .putString(KEY_PAIRING_KEY, value.trim().ifBlank { DEFAULT_PAIRING_KEY })
                .apply()
        }

    /** 是否仍为公开源码默认配对密钥（频道名可预测，存在伪造远程重置 PIN 指令风险）。 */
    val pairingKeyIsDefault: Boolean
        get() = pairingKey == DEFAULT_PAIRING_KEY

    // 受控端一般不设置默认目标（1:1 会话），peer 取当前 defaultTargetDeviceId（通常为 null）
    fun sendChatText(text: String) {
        transportManager.sendMessage(InkMessage.text(MessageType.CHAT_TEXT, text, from = deviceId))
        chatStore.add(
            ChatMessage(chatStore.nextId(), MessageType.CHAT_TEXT, text, deviceId, System.currentTimeMillis()),
            transportManager.defaultTargetDeviceId
        )
    }

    fun sendChatImage(base64: String) {
        transportManager.sendMessage(InkMessage.text(MessageType.CHAT_IMAGE, base64, from = deviceId))
        chatStore.add(
            ChatMessage(chatStore.nextId(), MessageType.CHAT_IMAGE, base64, deviceId, System.currentTimeMillis()),
            transportManager.defaultTargetDeviceId
        )
    }

    fun sendChatAudio(base64: String) {
        transportManager.sendMessage(InkMessage.text(MessageType.CHAT_AUDIO, base64, from = deviceId))
        chatStore.add(
            ChatMessage(chatStore.nextId(), MessageType.CHAT_AUDIO, base64, deviceId, System.currentTimeMillis()),
            transportManager.defaultTargetDeviceId
        )
    }

    override fun onCreate() {
        super.onCreate()
        deviceId = DeviceIdProvider.getDeviceId(this)
        transportManager = TransportManager(
            deviceId = deviceId,
            localRole = LocalWsTransport.LocalRole.SERVER
        )
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "inklink_host"
        private const val KEY_RELAY_URL = "relay_server_url"
        private const val KEY_TRANSPORT_MODE = "transport_mode"
        private const val KEY_ABLY_KEY = "ably_key"

        const val KEY_PAIRING_KEY = "pairing_key"
        const val DEFAULT_PAIRING_KEY = "inklink_default_key"
        const val MODE_LOCAL = "local"
        const val MODE_RELAY = "relay"
        const val MODE_ABLY = "ably"
    }
}
