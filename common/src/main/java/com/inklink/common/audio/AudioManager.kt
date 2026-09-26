package com.inklink.common.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * 音频采集与播放控制（前台服务内托管，双端共用）。
 *
 * 参数：8000Hz / 16bit / 单声道，20ms 一帧（320B）。
 * - 采集：[AudioRecord]，静音检测阈值以下不回调，节省流量。
 * - 播放：[AudioTrack] + jitter buffer（约 5 帧），按序号排序，跳号静音填充。
 * - 回声消除：优先启用原生 [android.media.audiofx.AcousticEchoCanceler]。
 *
 * 采播独立生命周期：可只采集不播放、或只播放不采集。
 */
class AudioManager {

    private val running = AtomicBoolean(false)

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var recordThread: Thread? = null
    private var aec: android.media.audiofx.AcousticEchoCanceler? = null

    // jitter buffer：按序号排序，容量约 5 帧
    private val playQueue = ArrayBlockingQueue<AudioPacket>(JITTER_FRAMES)
    private val nextExpectedSeq = AtomicInteger(-1)

    private val sampleRate = AudioPacket.SAMPLE_RATE
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var onFrame: ((AudioPacket) -> Unit)? = null
    private var silenceThreshold = DEFAULT_SILENCE_THRESHOLD

    /** 开始采集。每 20ms 产生一帧，静音帧丢弃，非静音帧回调 [onFrame]。 */
    @Suppress("MissingPermission")
    fun startRecord(onFrame: (AudioPacket) -> Unit) {
        if (running.get()) return
        this.onFrame = onFrame

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufSize = maxOf(minBuf, AudioPacket.PCM_FRAME_SIZE * 2)

        record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channelConfig, audioFormat, bufSize
        )
        if (record?.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            record = null
            return
        }

        // 全双工回声消除：优先启用原生 AEC，硬件不支持时静默降级
        record?.audioSessionId?.let { sessionId ->
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                aec = android.media.audiofx.AcousticEchoCanceler.create(sessionId)?.apply {
                    enabled = true
                }
            }
        }

        running.set(true)
        record?.startRecording()
        recordThread = Thread { captureLoop() }.apply { isDaemon = true; start() }
    }

    /** 停止采集。 */
    fun stopRecord() {
        running.set(false)
        recordThread?.interrupt()
        recordThread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        aec?.let {
            runCatching { it.enabled = false }
            it.release()
        }
        aec = null
        onFrame = null
    }

    private fun captureLoop() {
        val seq = AtomicInteger(0)
        val buffer = ByteArray(AudioPacket.PCM_FRAME_SIZE)
        while (running.get()) {
            val read = record?.read(buffer, 0, buffer.size) ?: -1
            if (read <= 0) continue
            val frame = if (read < buffer.size) buffer.copyOf(read) else buffer
            if (isSilence(frame)) continue
            onFrame?.invoke(AudioPacket(sequence = seq.getAndIncrement() and AudioPacket.MAX_SEQUENCE, pcm = frame))
        }
    }

    /** 播放一帧语音。内部按序号排序，跳号用静音补齐。 */
    fun play(frame: ByteArray) {
        val packet = AudioPacket.fromFrame(frame) ?: return
        ensureTrack()

        if (nextExpectedSeq.get() < 0) {
            nextExpectedSeq.set(packet.sequence)
        }

        val expected = nextExpectedSeq.get()
        if (packet.sequence == expected) {
            playQueue.offer(packet)
            nextExpectedSeq.set((expected + 1) and AudioPacket.MAX_SEQUENCE)
        } else if (AudioPacket.isNewerOrEqual(packet.sequence, expected)) {
            // 存在跳号：用静音补齐缺失帧
            val gap = (packet.sequence - expected) and AudioPacket.MAX_SEQUENCE
            repeat(minOf(gap, AudioPacket.MAX_SEQUENCE / 2)) {
                playQueue.offer(AudioPacket(expected, ByteArray(AudioPacket.PCM_FRAME_SIZE)))
            }
            nextExpectedSeq.set((packet.sequence + 1) and AudioPacket.MAX_SEQUENCE)
        }
        // 迟到帧（seq < expected）直接丢弃
        drainQueue()
    }

    private fun ensureTrack() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, audioFormat)
        track = AudioTrack(
            android.media.AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            audioFormat,
            maxOf(minBuf, AudioPacket.PCM_FRAME_SIZE * 4),
            AudioTrack.MODE_STREAM
        )
        track?.play()
    }

    private fun drainQueue() {
        val t = track ?: return
        while (playQueue.isNotEmpty()) {
            val packet = playQueue.poll() ?: break
            t.write(packet.pcm, 0, packet.pcm.size)
        }
    }

    private fun isSilence(pcm: ByteArray): Boolean {
        var sum = 0L
        var i = 0
        while (i < pcm.size - 1) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            sum += abs(sample.toShort().toInt())
            i += 2
        }
        val avg = if (pcm.isEmpty()) 0 else (sum / (pcm.size / 2))
        return avg < silenceThreshold
    }

    /** 释放所有音频资源。 */
    fun release() {
        stopRecord()
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
        nextExpectedSeq.set(-1)
        playQueue.clear()
    }

    companion object {
        private const val JITTER_FRAMES = 5
        private const val DEFAULT_SILENCE_THRESHOLD = 300L
    }
}
