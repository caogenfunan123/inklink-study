package com.inklink.host.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.inklink.common.protocol.payload.AckPayload

/**
 * 警报蜂鸣管理器：通过 STREAM_ALARM 播放紧急警报音 + 持续脉冲震动兜底。
 */
class SirenManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var toneGenerator: ToneGenerator? = null
    private var isRinging = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null
    private var previousVolume = -1
    private var vibrator: Vibrator? = null

    var onStateChanged: ((Boolean) -> Unit)? = null

    private fun resolveVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun startVibration() {
        runCatching {
            val v = resolveVibrator() ?: return
            vibrator = v
            // 600ms 震 + 400ms 停 持续脉冲，直到 stopRing 取消
            val pattern = longArrayOf(0, 600, 400)
            v.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }

    private fun stopVibration() {
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    /**
     * 触发强制响铃。
     * @return 返回 ACK 状态码：0 正常，101 音量受限
     */
    fun startRing(durationSec: Int = 15, volumePct: Int = 100): Int {
        stopRing()
        var ackCode = AckPayload.CODE_OK

        // 1. 尝试最大化警报音量
        audioManager?.let { am ->
            try {
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                previousVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                val targetVol = (maxVol * (volumePct.coerceIn(10, 100) / 100f)).toInt().coerceAtLeast(1)
                am.setStreamVolume(AudioManager.STREAM_ALARM, targetVol, 0)
            } catch (e: Exception) {
                ackCode = AckPayload.CODE_VOLUME_RESTRICTED
            }
        }

        // 2. 启动 ToneGenerator 播放紧急警报音频 + 脉冲震动兜底
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK)
            startVibration()
            isRinging = true
            onStateChanged?.invoke(true)
        } catch (e: Exception) {
            ackCode = AckPayload.CODE_EXECUTION_ERROR
            isRinging = false
            stopVibration()
            return ackCode
        }

        // 3. 设定超时自动停止
        val timeoutMs = (durationSec.coerceIn(3, 60)) * 1000L
        stopRunnable = Runnable { stopRing() }
        mainHandler.postDelayed(stopRunnable!!, timeoutMs)

        return ackCode
    }

    /**
     * 立即停止响铃并恢复此前音量。
     */
    fun stopRing() {
        stopRunnable?.let { mainHandler.removeCallbacks(it) }
        stopRunnable = null

        if (isRinging) {
            try {
                toneGenerator?.stopTone()
                toneGenerator?.release()
            } catch (ignored: Exception) {}
            toneGenerator = null
            stopVibration()
            isRinging = false
            onStateChanged?.invoke(false)

            // 恢复音量
            if (previousVolume != -1) {
                audioManager?.runCatching {
                    setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
                }
                previousVolume = -1
            }
        }
    }

    fun isRinging(): Boolean = isRinging
}
