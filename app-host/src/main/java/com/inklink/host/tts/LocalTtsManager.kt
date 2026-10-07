package com.inklink.host.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 本地儿童 TTS 朗读管理器与音频焦点调度。
 *
 * 引擎降级策略（2026-08-30 修复"只有语音没声音"）：
 * 1. 默认引擎 onInit 失败时，遍历系统已装引擎列表，挑选支持中文的引擎重建；
 * 2. 中文语言数据缺失时尝试英文兜底；
 * 3. 全部失败：回调 [onUnavailable]（UI 侧降级为文字气泡），绝不静默吞掉。
 */
class LocalTtsManager(
    private val context: Context,
    /**
     * 引擎就绪回调（2026-08-10 新增，供音频系统做「启动检测一次 + 保存布尔标记」）。
     * 刻意放在 onUnavailable **之前**：现存调用点用的是尾随 lambda
     * `LocalTtsManager(this) { 降级为文字气泡 }`，尾随 lambda 绑定最后一个参数，
     * 若把 onReady 追加到末位，这段降级逻辑会被静默解释成"就绪时执行"——语义完全反过来。
     */
    private val onReady: (() -> Unit)? = null,
    private val onUnavailable: (() -> Unit)? = null
) : TextToSpeech.OnInitListener {

    /** 引擎是否可用（多引擎重试成功后为 true；全部失败为 false） */
    var isReady: Boolean = false
        private set

    private var readyNotified = false

    private var tts: TextToSpeech? = null
    private var initAttempts = 0
    private var engineQueue: MutableList<String>? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    /**
     * TTS 引擎异步初始化完成前收到的待朗读队列。
     *
     * 早期是单槽 pendingSpeech：连发两声时第二次赋值直接覆盖第一次，
     * 第一次的 onDone 永不回调（TaskPlayActionReceiver 靠 15s 兜底才回收
     * PendingResult），且 utteranceId 同为「tts_毫秒」同毫秒会撞号导致回调错配。
     */
    private val pendingSpeech = ArrayDeque<Pair<String, (() -> Unit)?>>()

    /** 朗读完成回调表：utteranceId -> onDone。单一监听器分发表，避免每次 speak
     *  重建 listener 把上一条朗读的 onDone 挤掉。 */
    private val doneCallbacks = java.util.concurrent.ConcurrentHashMap<String, (() -> Unit)?>()
    private val utteranceSeq = java.util.concurrent.atomic.AtomicLong(0L)

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                stop()
            }
        }
    }

    init {
        startInit(null)
    }

    private fun startInit(engine: String?) {
        initAttempts++
        tts = if (engine != null) {
            TextToSpeech(context.applicationContext, this, engine)
        } else {
            TextToSpeech(context.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.CHINESE)
            val zhOk = (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED)
            if (zhOk) {
                isReady = true
                tts?.setPitch(1.1f) // 儿童风格稍高音调
                tts?.setSpeechRate(0.95f)
            } else {
                // 中文数据缺失：尝试英文兜底
                val enResult = tts?.setLanguage(Locale.ENGLISH)
                isReady = (enResult != TextToSpeech.LANG_MISSING_DATA && enResult != TextToSpeech.LANG_NOT_SUPPORTED)
            }
        }
        if (isReady && !readyNotified) {
            readyNotified = true
            onReady?.invoke()
        }
        if (!isReady) {
            // 引擎失败：遍历已装引擎重试（每引擎一次，避免无限循环）
            val engines = runCatching { tts?.engines ?: emptyList() }.getOrDefault(emptyList())
            val engineNames = engines.mapNotNull { it.name }
            if (engineQueue == null && engineNames.isNotEmpty()) {
                engineQueue = engineNames.toMutableList()
            }
            val next = engineQueue?.firstOrNull { it != (tts?.defaultEngine) }
            if (next != null && initAttempts < 8) {
                engineQueue?.removeAt(engineQueue?.indexOf(next) ?: 0)
                runCatching { tts?.shutdown() }
                isReady = false
                startInit(next)
                return
            }
            runCatching { tts?.shutdown() }
            tts = null
            isReady = false
            onUnavailable?.invoke()
        }
        // 初始化完成（无论成败）后补读 pending，避免 BroadcastReceiver 短生命周期调用被静默吞掉
        if (pendingSpeech.isNotEmpty()) {
            val queued = pendingSpeech.toList()
            pendingSpeech.clear()
            queued.forEach { (text, onDone) ->
                if (isReady) speak(text, onDone) else onDone?.invoke()
            }
        }
    }

    /** 引擎就绪后注册的唯一进度监听器：按 utteranceId 分发给对应回调。 */
    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            abandonAudioFocus()
            utteranceId?.let { doneCallbacks.remove(it) }?.invoke()
        }
        override fun onError(utteranceId: String?) {
            abandonAudioFocus()
            utteranceId?.let { doneCallbacks.remove(it) }?.invoke()
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        if (!isReady) {
            // 引擎未就绪：入队暂存，onInit 后自动补读；引擎彻底不可用则降级回调
            if (tts == null) {
                onDone?.invoke()
            } else {
                pendingSpeech.addLast(text to onDone)
            }
            return
        }

        requestAudioFocus()

        // 自增序列：毫秒时间戳会在同毫秒两条朗读时撞号，导致 A 的 onDone 触发 B 的 finish
        val utteranceId = "tts_${utteranceSeq.incrementAndGet()}"
        if (onDone != null) {
            doneCallbacks[utteranceId] = onDone
        }
        tts?.setOnUtteranceProgressListener(utteranceListener)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        tts?.stop()
        abandonAudioFocus()
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            focusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        isReady = false
        readyNotified = false
        pendingSpeech.clear()
        // 未兑现的回调补发，避免调用方（如 TaskPlayActionReceiver 的 finish）悬挂
        doneCallbacks.values.toList().also { doneCallbacks.clear() }.forEach { it?.invoke() }
    }
}
