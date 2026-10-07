package com.inklink.host.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Build

/**
 * 8-bit 风格音效（V1.1 任务 9.9）：
 * ToneGenerator 方波音符串合成 chiptune 小旋律（零音频资产），
 * 系统 FX 作为底噪点缀；静音开关统一走 [setMute]。
 */
class SoundEffectManager(private val context: Context) {

    /** 游戏化事件音效类目 */
    enum class Sfx { EAT, PLAY, READ, WATER, SNORE, GROAN, COIN, GIFT, HATCH, LEVEL_UP }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var soundPool: SoundPool? = null
    private var isMuted: Boolean = false

    /** release 后禁止再播放：toneGen 是 lazy，播放会在 release 之后把它重新建出来。 */
    private var released = false

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()
    }

    private val toneGen: ToneGenerator? by lazy {
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
    }

    /** 音符串延时宿主：成员 Handler + release 时统一 removeCallbacks，
     *  早期版本每次 play 新建 Handler，播放中的音符回调会把已 release 的实例拖住。 */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 方波音符串（freq, 时长ms）——chiptune 旋律表。 */
    private val melodies: Map<Sfx, Array<IntArray>> = mapOf(
        // freq: 0 = 休止。音名近似 C5=523 D5=587 E5=659 G5=784 A5=880 C6=1046 E6=1318 G6=1568
        Sfx.EAT to arrayOf(intArrayOf(392, 60), intArrayOf(523, 60), intArrayOf(659, 90)),
        Sfx.PLAY to arrayOf(intArrayOf(523, 50), intArrayOf(659, 50), intArrayOf(784, 50), intArrayOf(1046, 90)),
        Sfx.READ to arrayOf(intArrayOf(659, 70), intArrayOf(587, 70), intArrayOf(523, 110)),
        Sfx.WATER to arrayOf(intArrayOf(1046, 40), intArrayOf(1318, 40), intArrayOf(1568, 40), intArrayOf(1318, 60)),
        Sfx.SNORE to arrayOf(intArrayOf(196, 160), intArrayOf(165, 200)),
        Sfx.GROAN to arrayOf(intArrayOf(330, 120), intArrayOf(262, 120), intArrayOf(196, 200)),
        Sfx.COIN to arrayOf(intArrayOf(988, 55), intArrayOf(1319, 130)),
        Sfx.GIFT to arrayOf(intArrayOf(784, 60), intArrayOf(988, 60), intArrayOf(1175, 60), intArrayOf(1568, 130)),
        Sfx.HATCH to arrayOf(intArrayOf(523, 50), intArrayOf(0, 40), intArrayOf(659, 50), intArrayOf(784, 50), intArrayOf(1046, 140)),
        Sfx.LEVEL_UP to arrayOf(intArrayOf(659, 60), intArrayOf(784, 60), intArrayOf(988, 60), intArrayOf(1319, 150))
    )

    /** 播放 chiptune 音效（非阻塞，失败静默）。 */
    fun play(sfx: Sfx) {
        if (isMuted || released) return
        val notes = melodies[sfx] ?: return
        val gen = toneGen ?: return
        var offset = 0
        for ((freq, dur) in notes) {
            handler.postDelayed({
                runCatching {
                    if (isMuted) return@postDelayed
                    if (freq == 0) gen.startTone(ToneGenerator.TONE_PROP_ACK, 1)
                    else gen.startTone(freqToTone(freq), dur)
                }
            }, offset.toLong())
            offset += dur + 20
        }
    }

    /** ToneGenerator 没有任意频率 API，取最接近的 DTMF/语音音组做 8-bit 感近似。 */
    private fun freqToTone(freq: Int): Int = when {
        freq < 250 -> ToneGenerator.TONE_DTMF_1
        freq < 350 -> ToneGenerator.TONE_DTMF_2
        freq < 450 -> ToneGenerator.TONE_DTMF_3
        freq < 560 -> ToneGenerator.TONE_DTMF_4
        freq < 700 -> ToneGenerator.TONE_DTMF_5
        freq < 850 -> ToneGenerator.TONE_DTMF_6
        freq < 1000 -> ToneGenerator.TONE_DTMF_7
        freq < 1250 -> ToneGenerator.TONE_DTMF_8
        freq < 1500 -> ToneGenerator.TONE_DTMF_9
        else -> ToneGenerator.TONE_DTMF_0
    }

    fun playFeedSound() {
        if (isMuted) return
        // 播放系统点击声或内置音频
        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK)
    }

    fun playAlarm(alarmAction: () -> Unit) {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        if (currentVol < maxVol / 2) {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
        }
        alarmAction()
    }

    fun setMute(muted: Boolean) {
        isMuted = muted
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        soundPool?.release()
        soundPool = null
        // ToneGenerator 持有一条原生音频通道：不 release 会随每个 Activity 实例泄漏，
        // 长会话后 AudioFlinger 资源紧张、新建 ToneGenerator 静默失败变成"没音效"
        runCatching { toneGen?.release() }
    }
}
