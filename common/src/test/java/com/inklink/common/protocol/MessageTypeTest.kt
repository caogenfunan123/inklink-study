package com.inklink.common.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTypeTest {

    @Test
    fun `枚举编号唯一`() {
        val codes = MessageType.entries.map { it.code }
        assertEquals("存在编号冲突", codes.size, codes.toSet().size)
    }

    @Test
    fun `fromCode 可回查全部枚举`() {
        MessageType.entries.forEach { type ->
            assertEquals(type, MessageType.fromCode(type.code))
        }
    }

    @Test
    fun `未知编号返回 null`() {
        assertNull(MessageType.fromCode(-1))
        assertNull(MessageType.fromCode(100))
    }
}
