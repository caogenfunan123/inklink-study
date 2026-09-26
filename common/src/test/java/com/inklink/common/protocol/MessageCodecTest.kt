package com.inklink.common.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageCodecTest {

    @Test
    fun `文本消息 round-trip`() {
        val original = InkMessage.text(
            type = MessageType.TEXT,
            payload = "你好，投屏",
            from = "device-A",
            target = "device-B"
        )
        val json = MessageCodec.encodeText(original)
        val decoded = MessageCodec.decodeText(json)

        assertEquals(original.type, decoded.type)
        assertEquals(original.payload, decoded.payload)
        assertEquals(original.fromDeviceId, decoded.fromDeviceId)
        assertEquals(original.targetDeviceId, decoded.targetDeviceId)
        assertNull(decoded.audioData)
    }

    @Test
    fun `GPS 消息 round-trip`() {
        val original = InkMessage(
            type = MessageType.GPS_REPORT.code,
            payload = """{"lat":31.2304,"lng":121.4737,"speed":0.0,"time":1724800000000}""",
            fromDeviceId = "device-A"
        )
        val decoded = MessageCodec.decodeText(MessageCodec.encodeText(original))
        assertEquals(original.payload, decoded.payload)
        assertEquals(MessageType.GPS_REPORT, decoded.messageType)
    }

    @Test
    fun `非法 JSON 解析返回 null`() {
        assertNull(MessageCodec.decodeTextOrNull("not a json"))
    }

    @Test
    fun `控制消息 round-trip`() {
        val original = InkMessage.control(MessageType.HEARTBEAT, from = "device-A")
        val decoded = MessageCodec.decodeText(MessageCodec.encodeText(original))
        assertEquals(MessageType.HEARTBEAT.code, decoded.type)
        assertEquals("device-A", decoded.fromDeviceId)
        assertNull(decoded.payload)
    }

    @Test
    fun `音频数据不进入文本序列化`() {
        val bytes = ByteArray(320) { (it % 256).toByte() }
        val original = InkMessage.audio(bytes, from = "device-A")
        val json = MessageCodec.encodeText(original)
        val decoded = MessageCodec.decodeText(json)
        assertNull(decoded.audioData)
    }
}
