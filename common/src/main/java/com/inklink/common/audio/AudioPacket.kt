package com.inklink.common.audio

import com.inklink.common.protocol.MessageType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 语音数据包封装。
 *
 * 二进制帧格式（与 [com.inklink.common.protocol.MessageType.AUDIO_DATA] 对应）：
 * ```
 * [0]            : byte 8 (AUDIO_DATA)
 * [1..2]         : uint16 序号 sequence (小端)
 * [3..]          : PCM 8000Hz/16bit/单声道
 * ```
 *
 * 序号用于接收端丢包检测与 jitter buffer 排序，回绕按无符号 16 位处理。
 */
data class AudioPacket(
    val sequence: Int,
    val pcm: ByteArray
) {
    init {
        require(sequence in 0..MAX_SEQUENCE) { "语音序号越界: $sequence" }
        require(pcm.isNotEmpty()) { "语音数据包不能为空" }
    }

    fun toFrame(): ByteArray {
        val frame = ByteBuffer.allocate(HEADER_SIZE + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        frame.put(MessageType.AUDIO_DATA.code.toByte())
        frame.putShort(sequence.toShort())
        frame.put(pcm)
        return frame.array()
    }

    companion object {
        const val HEADER_SIZE = 3
        const val MAX_SEQUENCE = 0xFFFF
        const val SAMPLE_RATE = 8000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val FRAME_MILLIS = 20

        /** 20ms @ 8000Hz/16bit/单声道 = 320 字节 */
        const val PCM_FRAME_SIZE = SAMPLE_RATE * FRAME_MILLIS / 1000 * BITS_PER_SAMPLE / 8 * CHANNELS

        /** 序号是否在新数据包为更优序号（处理回绕）。 */
        fun isNewerOrEqual(a: Int, b: Int): Boolean {
            val delta = (a - b) and MAX_SEQUENCE
            return delta < 0x8000 || delta == 0
        }

        fun fromFrame(frame: ByteArray): AudioPacket? {
            if (frame.size < HEADER_SIZE) return null
            val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.get().toInt() and 0xFF != MessageType.AUDIO_DATA.code) return null
            val sequence = buffer.getShort().toInt() and MAX_SEQUENCE
            val pcm = ByteArray(frame.size - HEADER_SIZE)
            buffer.get(pcm)
            return AudioPacket(sequence = sequence, pcm = pcm)
        }
    }
}
