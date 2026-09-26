package com.inklink.common.protocol

/**
 * 聊天消息（双端聊天页面的气泡展示单元）。
 *
 * [payload] 含义随 [type] 变化：
 * - CHAT_TEXT：纯文本内容
 * - CHAT_IMAGE：Base64 编码的图片
 * - CHAT_AUDIO：Base64 编码的音频（AMR）
 */
data class ChatMessage(
    val id: Long,
    val type: MessageType,
    val payload: String,
    val fromDeviceId: String?,
    val timestamp: Long
)
