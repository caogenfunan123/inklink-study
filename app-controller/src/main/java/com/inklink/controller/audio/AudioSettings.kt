package com.inklink.controller.audio

import android.content.Context
import com.inklink.common.protocol.payload.SoundProtocol

/**
 * 主控端「启用TTS文字播报」全局开关（《音频系统 Final-Rev1》§六）。
 *
 * 关闭时**连 ttsText 都不发出**（`enableTts=false` 且文本置空），而不是发了让受控端丢弃：
 * 少一个从网络进入的任意文本注入面，也省一份流量。
 *
 * 默认开启：文档定位是"可选附加能力 + 完整降级"，而受控端自己会判引擎可用性，
 * 不支持的手表会自动静默跳过——默认开不影响主业务，默认关则会让新功能看起来像没做。
 */
object AudioSettings {

    private const val PREFS = "inklink_controller"
    private const val KEY_TTS_ENABLED = "audio_tts_enabled"

    fun isTtsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_TTS_ENABLED, true)

    fun setTtsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TTS_ENABLED, enabled).apply()
    }

    /** 开关关 / 文本清洗后为空 → 返回空串，表示这条指令不携带播报文本。 */
    fun ttsTextFor(context: Context, raw: String?): String =
        if (isTtsEnabled(context)) SoundProtocol.sanitizeTts(raw) else ""
}
