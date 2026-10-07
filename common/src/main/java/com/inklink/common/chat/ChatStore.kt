package com.inklink.common.chat

import com.inklink.common.protocol.ChatMessage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 聊天消息存储（进程级，双端各自维护一份，Activity 切换不丢）。
 *
 * 本期仅内存保存，App 重启后清空；消息按追加顺序排列。
 *
 * 会话维度：每条消息带 [peerDeviceId]（对端设备）。主控端管理多个受控设备时，
 * 聊天页只渲染当前选中设备的时间线，避免多孩串台；peer 为 null 表示未定向
 * （受控端未设置默认目标），此时按全量时间线渲染。
 */
class ChatStore {

    interface Listener {
        fun onChatChanged()
    }

    /** 会话条目：消息本体 + 对端设备（多孩设备分流用）。 */
    private data class Stored(
        val message: ChatMessage,
        val peerDeviceId: String?
    )

    private val messages = CopyOnWriteArrayList<Stored>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    // 网络回调线程与 UI 线程都会 add()：非原子 ++ 会发出重复 id
    private val seq = AtomicLong(0L)

    fun nextId(): Long = seq.incrementAndGet()

    /** 追加一条会话消息。[peerDeviceId] 为该消息的对端设备（传入=受控端设备号）。 */
    fun add(message: ChatMessage, peerDeviceId: String?) {
        messages.add(Stored(message, peerDeviceId))
        listeners.forEach { it.onChatChanged() }
    }

    /**
     * 取时间线：[peerDeviceId] 非空时只取该对端设备的会话；为 null 时取全量
     * （对应受控端未定向默认目标的场景）。
     */
    fun all(peerDeviceId: String?): List<ChatMessage> =
        if (peerDeviceId == null) {
            messages.map { it.message }
        } else {
            messages.filter { it.peerDeviceId == peerDeviceId }.map { it.message }
        }

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
