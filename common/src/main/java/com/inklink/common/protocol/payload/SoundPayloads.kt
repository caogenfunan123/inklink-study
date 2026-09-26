package com.inklink.common.protocol.payload

import com.google.gson.annotations.SerializedName

/**
 * 互动音频系统载荷（《音频系统 Final-Rev1》§四）。
 *
 * 设计约束：
 * - **不传音频二进制**，只传 ID 与简短文本；`AUDIO_DATA(8)` 那条二进制链路只用于局域网实时对讲，与本系统无关。
 * - 预制音效是必选主路径，TTS 文字播报是可选附加项：`enableTts=false` 或 `ttsText` 为空即只发声。
 * - ID 一律白名单校验（见 [SoundProtocol]），未知 ID 直接拒绝，不做兜底播放。
 */

/** 通用提示音指令 (CMD_PLAY_SOUND / 22) */
data class PlaySoundPayload(
    @SerializedName("soundId") val soundId: String = "",
    @SerializedName("ttsText") val ttsText: String = "",
    @SerializedName("enableTts") val enableTts: Boolean = false,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/** 宠物行为事件指令 (CMD_PET_EVENT / 23) */
data class PetEventPayload(
    @SerializedName("eventId") val eventId: String = "",
    @SerializedName("ttsText") val ttsText: String = "",
    @SerializedName("enableTts") val enableTts: Boolean = false,
    @SerializedName("triggerTs")
        /* 墙钟是**对的**：这是对端时钟域的事件时刻，只作幂等键的一部分与展示用，
           永远不要拿它和本地时间相减做窗口判据（那种判据一律用 MonoClock/MonoThrottle）。
           同理不要"统一"成 elapsedRealtime——两台设备的单调基准互不可比。 */
        val triggerTs: Long = System.currentTimeMillis()
)

/**
 * 事件到业务的绑定：受控端收到 eventId 后要做的事（播哪个音效 + 是否结算属性 + 结算成什么）。
 *
 * [action] 取值刻意与 [PetInteractCmdPayload.action] 同域（FEED/PLAY/CLEAN/SLEEP/TOUCH…），
 * 目的是让 23 能直接复用 31 的结算函数，而不是再实现一遍数值模型。
 * [sleeping] 仅对 SLEEP 类事件有意义：显式目标态，避免用 toggle 语义导致「连点两次反而没睡」。
 */
data class PetEventBinding(
    val soundId: String,
    val action: String? = null,
    val count: Int = 1,
    val sleeping: Boolean? = null,
    /** 是否由受控端主动上报（主控端只展示、不再回声发送） */
    val hostReported: Boolean = false
)

/**
 * 音频协议白名单与零信任截断（双端共用唯一真值源）。
 *
 * 为什么白名单放 common：主控端要按同一张表决定按钮发什么、受控端要按同一张表拒绝伪造包。
 * 两侧各写一份必然漂移（历史上渲染模式表就差点这么翻车）。
 */
object SoundProtocol {

    /** 系统类提示音：ID == res/raw 文件名词干，无反射查找、无映射表漂移 */
    val SOUND_IDS = setOf("alert_call", "alert_notify", "alert_warn", "sound_ack")

    /** 事件 ID → 绑定（含该事件必播的预制音效） */
    val EVENT_BINDINGS: Map<String, PetEventBinding> = mapOf(
        "event_feed" to PetEventBinding("pet_feed", action = "FEED", count = 1),
        "event_touch" to PetEventBinding("pet_touch", action = "TOUCH"),
        "event_happy" to PetEventBinding("pet_happy", action = "PLAY"),
        "event_sleep" to PetEventBinding("pet_sleep", action = "SLEEP", sleeping = true),
        "event_wakeup" to PetEventBinding("pet_wakeup", action = "SLEEP", sleeping = false),
        // 饥饿告警由受控端主动上报，不改数值（数值已经低了，再扣就是惩罚用户）
        "event_hungry_alert" to PetEventBinding("pet_hungry", hostReported = true)
    )

    val EVENT_IDS: Set<String> = EVENT_BINDINGS.keys

    /** 全部合法 raw 名（系统 4 + 事件 6 = 文档 §二 的 10 个预制音效） */
    val RAW_NAMES: Set<String> = SOUND_IDS + EVENT_BINDINGS.values.map { it.soundId }.toSet()

    /** 告警类音效：必须走 STREAM_ALARM 且打断 TTS（android-lead 音频通道隔离铁律） */
    val ALARM_SOUND_IDS = setOf("alert_warn", "alert_call")

    /**
     * TTS 文本硬上限。
     *
     * 零信任：`ttsText` 来自网络，等价于「让小孩手表念任意一句话」的注入面。
     * 只靠 64KB 包体上限不够——40 字以外的文本既没有产品价值，又给 TTS 引擎灌长任务。
     */
    const val TTS_TEXT_MAX_LEN = 40

    /**
     * 远程语音任务文案上限（REMOTE_TASK_SEND / 45）。
     * 家长写的任务文本天然比互动播报长（"先把数学作业第三页做完再玩"），沿用 40 字会静默截断；
     * 但旧实现完全没有上限——本次是**新加**约束而不是收紧既有约定。
     */
    const val TASK_TTS_MAX_LEN = 120

    /** 播报限流间隔（ms）：防止被构造的高频指令变成连续朗读，把语音通道变噪音攻击。 */
    const val TTS_MIN_GAP_MS = 1_500L

    fun isKnownSound(soundId: String): Boolean = SOUND_IDS.contains(soundId)

    fun bindingFor(eventId: String): PetEventBinding? = EVENT_BINDINGS[eventId]

    fun isAlarmSound(soundId: String): Boolean = ALARM_SOUND_IDS.contains(soundId)

    /**
     * 网络文本入本地前的截断清洗：去控制字符/换行折叠为空格/首尾去空白，硬裁到 [maxLen]。
     * 返回空串代表这条文本不该朗读（调用方据此只播音效，符合「TTS 只是锦上添花」）。
     */
    fun sanitizeTts(raw: String?, maxLen: Int = TTS_TEXT_MAX_LEN): String {
        if (raw.isNullOrBlank()) return ""
        val cleaned = buildString(minOf(raw.length, maxLen * 2)) {
            for (ch in raw) {
                when {
                    ch == '\n' || ch == '\r' || ch == '\t' -> append(' ')
                    ch.code < 0x20 || ch.code == 0x7F -> Unit   // 控制字符直接丢弃，不替换成 ?
                    else -> append(ch)
                }
            }
        }.trim()
        return clamp(cleaned, maxLen)
    }

    /**
     * 按**码点边界**裁剪。
     * 朴素的 `take(n)` 按 UTF-16 码元切，正好落在代理对中间时，emoji 会被切成半个——
     * 半个代理项送进 TTS 引擎是乱码或直接抛异常，而这种截断只在文本超长时才发生，
     * 平时测不出来（"我开心😊😊" 这种文案恰恰最容易踩）。
     */
    fun clamp(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        var cut = maxLen
        if (cut > 0 && Character.isHighSurrogate(text[cut - 1])) cut--   // 高代理在末尾 → 整对舍掉
        return text.substring(0, cut).trim()
    }

    /** 是否需要朗读：三条件同时成立（开关开、文本非空、清洗后仍有内容） */
    fun shouldSpeak(enableTts: Boolean, ttsText: String?): Boolean =
        enableTts && sanitizeTts(ttsText).isNotEmpty()
}
