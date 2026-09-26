package com.inklink.common.protocol

import java.util.concurrent.atomic.AtomicLong

/**
 * 双端统一消息载体。
 *
 * 文本/指令类消息使用 [payload]，语音数据使用 [audioData]（仅局域网）。
 * [msgId] 用于全链路 ACK 确认与幂等去重。
 */
data class InkMessage(
    val type: Int,
    val payload: String? = null,
    val audioData: ByteArray? = null,
    val targetDeviceId: String? = null,
    val fromDeviceId: String? = null,
    val address: String? = null,
    val msgId: String = nextMsgId(fromDeviceId),
    val timestamp: Long = System.currentTimeMillis()
) {
    val messageType: MessageType?
        get() = MessageType.fromCode(type)

    fun isAudio(): Boolean = type == MessageType.AUDIO_DATA.code

    companion object {
        /** Ably 单条消息上限 64KB（含 JSON 序列化后的完整包体） */
        const val MAX_MESSAGE_BYTES = 64 * 1024

        private val seq = AtomicLong(1000)

        fun nextMsgId(prefix: String?): String {
            val p = if (!prefix.isNullOrBlank()) prefix else "msg"
            return "$p-${System.currentTimeMillis()}-${seq.incrementAndGet()}"
        }

        fun text(
            type: MessageType,
            payload: String,
            from: String? = null,
            target: String? = null,
            msgId: String? = null
        ) = InkMessage(
            type = type.code,
            payload = payload,
            fromDeviceId = from,
            targetDeviceId = target,
            msgId = msgId ?: nextMsgId(from)
        )

        fun control(
            type: MessageType,
            from: String? = null,
            target: String? = null,
            payload: String? = null,
            msgId: String? = null
        ) = InkMessage(
            type = type.code,
            payload = payload,
            fromDeviceId = from,
            targetDeviceId = target,
            msgId = msgId ?: nextMsgId(from)
        )

        fun audio(
            audioData: ByteArray,
            from: String? = null,
            target: String? = null
        ) = InkMessage(
            type = MessageType.AUDIO_DATA.code,
            audioData = audioData,
            fromDeviceId = from,
            targetDeviceId = target
        )
    }
}
