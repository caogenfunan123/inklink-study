package com.inklink.common.chat

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * 聊天语音录制（AMR 窄带，体积小适合 Ably 64KB 文本消息承载）。
 *
 * 用法：按住录音按钮时 [start]，松开时 [stop] 返回音频字节（可能为 null）。
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var file: File? = null

    @Suppress("MissingPermission")
    fun start(): Boolean {
        stopInternal()
        val target = File(context.cacheDir, "chat_voice_${System.currentTimeMillis()}.amr")
        return runCatching {
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setAudioEncodingBitRate(12_200)
                setAudioSamplingRate(8_000)
                setOutputFile(target.absolutePath)
                prepare()
                start()
            }
            file = target
            true
        }.getOrDefault(false)
    }

    fun stop(): ByteArray? {
        val data = runCatching {
            recorder?.stop()
            recorder?.release()
            recorder = null
            file?.takeIf { it.exists() && it.length() > 0 }?.readBytes()
        }.getOrNull()
        // 临时 AMR 读完即删，避免 cacheDir 长期堆积
        file?.delete()
        file = null
        return data
    }

    fun isRecording(): Boolean = recorder != null

    private fun stopInternal() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        file?.delete()
        file = null
    }
}
