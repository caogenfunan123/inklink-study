package com.inklink.host.audio

import android.content.Context
import com.inklink.common.protocol.payload.SoundProtocol

/**
 * 音频反馈编排（《音频系统 Final-Rev1》§五 收消息固定流程的第 2、3 步）。
 *
 *     播预制音效（永远执行） → 若开关开且有文本且引擎可用 → 朗读
 *
 * 关键顺序：**音效先起、朗读排在音效完成之后**。两者同时起会把 0.5~1.2s 的 chiptune
 * 压在童声上面，手表小喇叭上表现为糊成一片；串起来后听感是"叮一声，再说话"。
 * 音效播放失败不阻断朗读（文档 §五 降级策略 2），朗读被跳过也不影响音效与状态结算。
 */
class PetAudioFeedback(private val context: Context) {

    /**
     * 一次反馈的结果，字段直接进 ACK，让主控端和真机验收都能看到"为什么没响/没说"。
     * 结果在 [PetAudioFeedback.feedback] 的回调里给出（等 MediaPlayer 完成/出错后才确定）。
     */
    data class Report(
        val sound: RawSoundPlayer.Result = RawSoundPlayer.Result.UNKNOWN_ID,
        val spoke: Boolean = false,
        val ttsNote: String? = null,
        val mediaMuted: Boolean = false
    ) {
        /** 人类可读摘要，写入事件日志/ACK note。空串=一切正常。 */
        fun summary(): String {
            val parts = mutableListOf<String>()
            parts += when (sound) {
                RawSoundPlayer.Result.PLAYED -> "音效已播"
                RawSoundPlayer.Result.SKIPPED_BY_ALARM -> "音效让路告警"
                RawSoundPlayer.Result.UNKNOWN_ID -> "音效ID非法"
                RawSoundPlayer.Result.FAILED_NO_ASSET -> "音效资产缺失"
                RawSoundPlayer.Result.FAILED_PLAY -> "音效播放失败"
            }
            if (!spoke) parts += "播报未播:${ttsNote ?: "未启用"}"
            if (mediaMuted) parts += "媒体音量为0"
            return parts.joinToString("；")
        }
    }

    private val player = RawSoundPlayer(context)
    private val tts get() = PetTtsGate.get(context)

    /** 启动探测（幂等）。服务 onCreate 调一次即可。 */
    fun start() = tts.start()   // 播放器无需预热；TTS 引擎异步初始化才是唯一耗时项

    /**
     * 播放 + 可选朗读。[onDone] 一定会被调用（含全部失败路径），调用方据此发 ACK。
     */
    fun feedback(soundId: String?, ttsText: String?, enableTts: Boolean, onDone: (Report) -> Unit) {
        val wantSpeak = SoundProtocol.shouldSpeak(enableTts, ttsText)
        val alarm = SoundProtocol.isAlarmSound(soundId ?: "")
        if (alarm) {
            // 告警强行打断正在进行的朗读（音频通道隔离铁律，优先级高于"新音频优先"）
            tts.stop()
        }
        if (soundId.isNullOrBlank()) {
            speakOrFinish(null, wantSpeak, ttsText, onDone)
            return
        }
        player.play(soundId) { res ->
            speakOrFinish(res, wantSpeak, ttsText, onDone)
        }
    }

    private fun speakOrFinish(
        soundRes: RawSoundPlayer.Result?,
        wantSpeak: Boolean,
        ttsText: String?,
        onDone: (Report) -> Unit
    ) {
        if (!wantSpeak) {
            onDone(
                Report(
                    sound = soundRes ?: RawSoundPlayer.Result.UNKNOWN_ID,
                    spoke = false,
                    ttsNote = if (soundRes == RawSoundPlayer.Result.SKIPPED_BY_ALARM) "告警让路，跳过朗读" else null,
                    mediaMuted = player.isMediaVolumeMuted()
                )
            )
            return
        }
        tts.speak(ttsText ?: "") { ok ->
            onDone(
                Report(
                    sound = soundRes ?: RawSoundPlayer.Result.UNKNOWN_ID,
                    spoke = ok,
                    ttsNote = if (ok) null else tts.lastSkipReason ?: "引擎不可用",
                    mediaMuted = player.isMediaVolumeMuted()
                )
            )
        }
    }

    /** 立即静音（用于服务销毁/用户主动停止）。 */
    fun stopAll() {
        player.stop()
        tts.stop()
    }

    fun release() {
        player.release()
        // TTS 单例不随本对象释放：TaskPlayActionReceiver / UI 可能仍在使用
    }
}
