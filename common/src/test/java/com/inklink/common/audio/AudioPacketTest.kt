package com.inklink.common.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPacketTest {

    @Test
    fun `组包解包 round-trip`() {
        val pcm = ByteArray(320) { (it % 256).toByte() }
        val packet = AudioPacket(sequence = 12345, pcm = pcm)
        val frame = packet.toFrame()

        assertEquals(320 + AudioPacket.HEADER_SIZE, frame.size)
        val decoded = AudioPacket.fromFrame(frame)
        assertEquals(12345, decoded?.sequence)
        assertArrayEquals(pcm, decoded?.pcm)
    }

    @Test
    fun `帧头类型错误返回 null`() {
        val frame = ByteArray(3) { 0 }
        frame[0] = 7 // 非 AUDIO_DATA
        assertNull(AudioPacket.fromFrame(frame))
    }

    @Test
    fun `帧过短返回 null`() {
        assertNull(AudioPacket.fromFrame(ByteArray(2)))
    }

    @Test
    fun `序号回绕判断`() {
        // 65535 -> 0 是正常递增回绕
        assertTrue(AudioPacket.isNewerOrEqual(0, 0xFFFF))
        assertTrue(AudioPacket.isNewerOrEqual(10, 9))
    }

    @Test
    fun `20ms 帧大小为 320 字节`() {
        assertEquals(320, AudioPacket.PCM_FRAME_SIZE)
    }
}
