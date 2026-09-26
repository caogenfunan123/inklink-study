package com.inklink.common.chat

import com.inklink.common.protocol.ChatMessage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 聊天消息存储（进程级，双端各自维护一份，Activity 切换不丢）。
 *
 * 本期仅内存保存，App 重启后清空；消息按追加顺序排列。
 */
class ChatStore {

    interface Listener {
        fun onChatChanged()
    }

    private val messages = CopyOnWriteArrayList<ChatMessage>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var seq = 0L

    fun nextId(): Long = ++seq

    fun add(message: ChatMessage) {
        messages.add(message)
        listeners.forEach { it.onChatChanged() }
    }

    fun all(): List<ChatMessage> = messages.toList()

    fun clear() {
        messages.clear()
        listeners.forEach { it.onChatChanged() }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }
}
