package com.inklink.common.chat

import com.inklink.common.protocol.ChatMessage
import com.inklink.common.protocol.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话时间线按 peer 分流的回归：多设备场景下 A 孩的消息不得串进 B 孩的时间线。
 */
class ChatStoreTest {

    private fun msg(id: Long, from: String, text: String) =
        ChatMessage(id, MessageType.CHAT_TEXT, text, from, 1_000L)

    @Test
    fun `all filters by peer`() {
        val store = ChatStore()
        store.add(msg(1, "dev-a", "A1"), "dev-a")
        store.add(msg(2, "dev-b", "B1"), "dev-b")
        store.add(msg(3, "dev-a", "A2"), "dev-a")

        val a = store.all("dev-a")
        assertEquals(listOf("A1", "A2"), a.map { it.payload })
        val b = store.all("dev-b")
        assertEquals(listOf("B1"), b.map { it.payload })
        // 未知设备不借别人的历史
        assertTrue(store.all("dev-c").isEmpty())
    }

    @Test
    fun `all with null peer returns whole timeline`() {
        val store = ChatStore()
        store.add(msg(1, "dev-a", "A1"), "dev-a")
        store.add(msg(2, "dev-b", "B1"), "dev-b")
        assertEquals(2, store.all(null).size)
    }

    @Test
    fun `add and clear notify listener, removal stops it`() {
        val store = ChatStore()
        var count = 0
        val listener = object : ChatStore.Listener {
            override fun onChatChanged() { count++ }
        }
        store.addListener(listener)
        store.add(msg(1, "dev-a", "A1"), "dev-a")
        store.add(msg(2, "dev-b", "B1"), "dev-b")
        assertEquals(2, count)

        store.clear()
        assertEquals(3, count)

        store.removeListener(listener)
        store.add(msg(3, "dev-a", "A3"), "dev-a")
        assertEquals(3, count)
        assertEquals(1, store.all("dev-a").size)
    }
}
