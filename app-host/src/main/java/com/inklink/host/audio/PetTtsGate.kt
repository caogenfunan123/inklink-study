package com.inklink.host.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.inklink.common.protocol.payload.SoundProtocol
import com.inklink.common.utils.MonoThrottle
import com.inklink.host.tts.LocalTtsManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 宠物播报门控（《音频系统 Final-Rev1》§九「TTS 工具类：初始化、可用性检测、朗读、销毁、降级」）。
 *
 * **进程内单例**，内部委托既有的 [LocalTtsManager]，而不是另起一个 TextToSpeech 实例：
 * 同进程双引擎会各自 bind 一次语音服务并抢同一份音频焦点（表现为"两句都在念、互相掐断"），
 * 且每个 `TextToSpeech` 都是一条到系统 TTS 服务的 Binder 连接。历史上
 * [com.inklink.host.receiver.TaskPlayActionReceiver] 就是每次广播 new 一个、从不 release。
 *
 * 可用性判定三态（[Probe]）：文档要求「启动时检测一次、保存布尔标记、上报主控」，
 * 但引擎初始化是异步的——把「还没测出来」当「不支持」上报，会让手表开机瞬间误显
 * "设备不支持文字播报"。故保留 UNKNOWN，并在 [SETTLE_TIMEOUT_MS] 内未收到任何回调时
 * 才判 UNAVAILABLE（部分精简 ROM 确实永不回调 onInit）。
 */
class PetTtsGate private constructor(private val context: Context) {

    enum class Probe { UNKNOWN, READY, UNAVAILABLE }

    @Volatile
    var probe: Probe = Probe.UNKNOWN
        private set

    /** 播报是否可用（只有明确 READY 才 true；UNKNOWN 视为不可用，不朗读） */
    val available: Boolean get() = probe == Probe.READY

    /** 主线程 Handler：既是媒体/TTS 的执行线程，也是延时任务的宿主（release 时必须 removeCallbacks，
     *  否则遗留的 Runnable 会在单例上一直持有引用）。 */
    private val handler = Handler(Looper.getMainLooper())
    private var manager: LocalTtsManager? = null
    private var settleTask: Runnable? = null

    /**
     * 播报限流。用 [MonoThrottle]（单调时钟）而不是墙钟：墙钟被往回调时
     * `now - last` 恒为负 → 每次都判"仍在限流窗口内" → 文字播报永久静默，
     * 而音效照常，症状极像"TTS 引擎坏了"。
     */
    private val speakThrottle = MonoThrottle(SoundProtocol.TTS_MIN_GAP_MS)
    private val listeners = CopyOnWriteArrayList<(Probe) -> Unit>()

    /**
     * 最近一次被跳过的原因。只进**本地事件日志**（经 PetAudioFeedback.Report.summary()
     * → appendLog），不写进 ACK payload：里面可能含本设备的单调时钟剩余毫秒，对端拿到
     * 无可比基准，绝不该参与任何判据。
     */
    @Volatile
    var lastSkipReason: String? = null
        private set

    @Volatile
    private var started = false

    /** 幂等启动：服务 onCreate 调一次即可（§七.3「受控端启动时做一次 TTS 可用性检测」）。 */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        handler.post { startOnMain() }
    }

    private fun startOnMain() {
        if (manager != null) return
        manager = LocalTtsManager(
            context.applicationContext,
            onReady = { setProbe(Probe.READY) },
            onUnavailable = { setProbe(Probe.UNAVAILABLE) }
        )
        val task = Runnable {
            if (probe == Probe.UNKNOWN) setProbe(Probe.UNAVAILABLE)
        }
        settleTask = task
        handler.postDelayed(task, SETTLE_TIMEOUT_MS)
    }

    /**
     * 只在**状态发生迁移**时回调（不立即回吐当前值）。
     * 早期版本注册即回调一次，结果 UI 侧"探测到不可用 → 播降级提示音"会在每次进入页面时
     * 凭空响一声。需要当前值的调用方直接读 [probe]。
     */
    fun addProbeListener(l: (Probe) -> Unit) {
        listeners.add(l)
    }

    fun removeProbeListener(l: (Probe) -> Unit) = listeners.remove(l)

    private fun setProbe(p: Probe) {
        if (probe == p) return
        // READY 之后不再被降级：多引擎重试过程中可能先报一次失败再换引擎成功，
        // 一旦真就绪就锁死，避免主控端提示文案来回跳。
        if (probe == Probe.READY && p == Probe.UNAVAILABLE) return
        probe = p
        handler.post { listeners.forEach { it(p) } }
    }

    /**
     * 朗读（可选附加功能）。返回回调 false = 未朗读但**不算失败**：预制音效主路径照常。
     *
     * [maxLen] 默认取互动播报的 40 字上限；远程任务文案（家长写的"先把数学作业第三页做完…"）
     * 本来就允许更长，调用方显式放宽，不能被互动播报的上限悄悄截断。
     */
    fun speak(raw: String, maxLen: Int = SoundProtocol.TTS_TEXT_MAX_LEN, onDone: (Boolean) -> Unit = {}) {
        handler.post { speakOnMain(raw, maxLen, onDone) }
    }

    private fun speakOnMain(raw: String, maxLen: Int, onDone: (Boolean) -> Unit) {
        val text = SoundProtocol.sanitizeTts(raw, maxLen)
        if (text.isEmpty()) {
            lastSkipReason = "文本为空"
            onDone(false)
            return
        }
        if (probe == Probe.UNAVAILABLE) {
            lastSkipReason = "设备无可用 TTS 引擎"
            onDone(false)
            return
        }
        // UNKNOWN（引擎还在初始化）不丢弃：LocalTtsManager 内部有 pendingSpeech 队列，
        // 就绪后自动补读。直接 return 会让冷启动头几次点击"没声音"，是体验倒退。
        if (manager == null) startOnMain()   // 已在主线程，直接建实例；start() 会再 post 一次导致本次拿不到 manager
        val m = manager
        if (m == null) {
            lastSkipReason = "TTS 实例未就绪"
            onDone(false)
            return
        }
        // 限流放在建实例之后、真正提交朗读之前才 tryAcquire：引擎侧提前 bail 不该占用窗口
        if (!speakThrottle.tryAcquire()) {
            lastSkipReason = "播报限流(剩余${speakThrottle.remainingMs()}ms)"
            onDone(false)
            return
        }
        lastSkipReason = null
        // LocalTtsManager 的 onDone 成功/失败都会回调（内部已 abandon 音频焦点），
        // 这里不回传引擎结果：能提交即视为已播报，引擎侧失败由它自己的降级回调处理。
        m.speak(text) { handler.post { onDone(true) } }
    }

    /** 强行打断当前朗读：告警音前置动作（音频通道隔离铁律）。 */
    fun stop() {
        handler.post { manager?.stop() }
    }

    @Synchronized
    fun release() {
        started = false
        handler.post { releaseOnMain() }
    }

    private fun releaseOnMain() {
        settleTask?.let { handler.removeCallbacks(it) }
        settleTask = null
        handler.removeCallbacksAndMessages(null)
        manager?.release()
        manager = null
        probe = Probe.UNKNOWN
        // 复位限流窗口：引擎重建后的第一次朗读不该继承上一条的冷却（否则重新初始化后
        // 头 1.5s 内的点击会静默无声，且原因藏在 lastSkipReason 里没人看）
        speakThrottle.reset()
    }

    companion object {
        private const val SETTLE_TIMEOUT_MS = 10_000L

        @Volatile
        private var instance: PetTtsGate? = null

        fun get(context: Context): PetTtsGate =
            instance ?: synchronized(this) {
                instance ?: PetTtsGate(context.applicationContext).also { instance = it }
            }

        /** 仅供单测复位全局单例。 */
        fun resetInstanceForTest() {
            instance?.release()
            instance = null
        }
    }
}
