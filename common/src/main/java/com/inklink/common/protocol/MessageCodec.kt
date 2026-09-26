package com.inklink.common.protocol

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * 消息编解码器。
 *
 * - 文本帧：`InkMessage` 序列化为 JSON（audioData 不参与文本帧，恒为 null）。
 * - 语音帧：独立二进制协议，见 [com.inklink.common.audio.AudioPacket]，不经过本类。
 */
object MessageCodec {

    private val gson: Gson = GsonBuilder().create()

    fun encodeText(message: InkMessage): String {
        // 文本帧强制忽略 audioData，语音二进制只走 AudioPacket，避免 Gson 序列化成 JSON 数组
        val toEncode = if (message.audioData != null) message.copy(audioData = null) else message
        return gson.toJson(toEncode)
    }

    fun decodeText(json: String): InkMessage {
        val msg = gson.fromJson(json, InkMessage::class.java)
        return msg ?: throw IllegalArgumentException("无法解析消息 JSON: $json")
    }

    fun decodeTextOrNull(json: String): InkMessage? = runCatching { decodeText(json) }.getOrNull()
}
