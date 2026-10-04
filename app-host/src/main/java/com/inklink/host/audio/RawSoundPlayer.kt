package com.inklink.host.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.inklink.common.protocol.payload.SoundProtocol
import com.inklink.host.R

/**
 * 预制音效播放器（《音频系统 Final-Rev1》§九「受控端 MediaPlayer 封装，管理预制音效播放、资源释放」）。
 *
 * 与 [com.inklink.host.util.SoundEffectManager] 的分工：本类负责**网络指令触达的 10 个标准音效**
 * （res/raw 里的 wav），SoundEffectManager 继续负责本地游戏化 chiptune（COIN/GIFT/HATCH/LEVEL_UP 等
 * 无音频资产的即时反馈）。刻意不合并：合并等于把"资产缺失"这一类降级风险引进已有链路。
 *
 * 三条实现约束（都是会踩的坑）：
 * 1. **每个 soundId 显式映射到 R.raw.\***，不用 `getIdentifier()` 反射查名——反射在 release 包
 *    受资源名混淆/裁剪影响，且拼错名字时静默返回 0（表现为"没声音但日志干净"）。
 * 2. **每个实例用完即 release**：MediaPlayer 持有 native 解码器与 AudioFlinger 会话，
 *    不 release 就是硬泄漏（手表内存小，几十个就够 OOM）。
 * 3. **告警音不被娱乐音抢占**：文档 §五「新音频优先，终止上一段」只在同类内成立，
 *    跨类必须让位于 android-lead 的音频通道隔离铁律（强控/告警可打断 TTS，反之不行）。
 */
class RawSoundPlayer(private val context: Context) {

    /** 输出通道。ALARM 走 STREAM_ALARM（不受媒体静音键影响），MUSIC 走 STREAM_MUSIC。 */
    enum class Route { MUSIC, ALARM }

    /** 播放结果：区分"不该响"和"响了但失败"，降级路径与真机排查看 ACK 就能定责。 */
    enum class Result { PLAYED, SKIPPED_BY_ALARM, UNKNOWN_ID, FAILED_NO_ASSET, FAILED_PLAY, INTERRUPTED }

    private var active: MediaPlayer? = null
    private var activeRoute: Route? = null
    private var activeDone: ((Result) -> Unit)? = null

    /**
     * 一切 MediaPlayer 构造/调用都必须落在主线程。
     * 硬事实：`onTextMessage` 由 Ably/WS 的接收线程回调（见 common/transport 的
     * `ably-heartbeat`/`local-reconnect` 线程池），而 `MediaPlayer` 构造函数内部
     * `new Handler()` 取当前线程 Looper——工作线程上没有 Looper，直接抛
     * "Can't create handler inside thread that has not called Looper.prepare()"。
     * 手法与既有 SirenManager 一致（它也是 mainHandler + postDelayed）。
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun rawResOf(soundId: String): Int? = when (soundId) {
        // 系统类
        "alert_call" -> R.raw.alert_call
        "alert_notify" -> R.raw.alert_notify
        "alert_warn" -> R.raw.alert_warn
        "sound_ack" -> R.raw.sound_ack
        // 宠物互动类
        "pet_feed" -> R.raw.pet_feed
        "pet_touch" -> R.raw.pet_touch
        "pet_happy" -> R.raw.pet_happy
        "pet_hungry" -> R.raw.pet_hungry
        "pet_sleep" -> R.raw.pet_sleep
        "pet_wakeup" -> R.raw.pet_wakeup
        else -> null
    }

    /** 该 ID 是否有本地资产（供 ACK 侧提前判定"音效文件缺失"降级）。 */
    fun hasAsset(soundId: String): Boolean = rawResOf(soundId) != null

    /**
     * 播放预制音效。[onDone] 在完成/出错/被后续音效打断时被调用一次（用于串接后续 TTS 朗读，
     * 避免音话重叠）。打断时结果为 [Result.INTERRUPTED]，由调用方决定是否继续串联。
     * 任何失败都不抛异常——音频坏了绝不能阻断宠物状态结算（文档 §八 铁律 3）。
     */
    fun play(soundId: String, onDone: (Result) -> Unit = {}) {
        mainHandler.post { playOnMain(soundId, onDone) }
    }

    private fun playOnMain(soundId: String, onDone: (Result) -> Unit) {
        val resId = rawResOf(soundId)
        if (resId == null) {
            finish(onDone, Result.UNKNOWN_ID)
            return
        }
        val route = if (SoundProtocol.isAlarmSound(soundId)) Route.ALARM else Route.MUSIC
        if (active != null && activeRoute == Route.ALARM && route == Route.MUSIC) {
            // 告警正在响：娱乐音让路，不混音也不掐告警
            finish(onDone, Result.SKIPPED_BY_ALARM)
            return
        }
        // 被新音效打断：先兑现旧回调再 release，否则"音效后接 TTS"的串联会永久悬挂
        val interrupted = activeDone
        releaseActive()
        interrupted?.invoke(Result.INTERRUPTED)

        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (route == Route.ALARM) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            context.resources.openRawResourceFd(resId).use { afd: AssetFileDescriptor ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.setOnCompletionListener {
                val cb = activeDone
                releaseActive()
                cb?.invoke(Result.PLAYED)
            }
            mp.setOnErrorListener { _, _, _ ->
                val cb = activeDone
                releaseActive()
                cb?.invoke(Result.FAILED_PLAY)
                true    // 已消费，避免系统再打一条 ERROR 日志
            }
            mp.prepare()
            mp.start()
            active = mp
            activeRoute = route
            activeDone = onDone
        } catch (e: Exception) {
            // 资源缺失/解码失败/ prepare 异常：静默降级，主业务继续
            runCatching { mp.release() }
            active = null
            activeRoute = null
            activeDone = null
            finish(onDone, Result.FAILED_NO_ASSET)
        }
    }

    /** 立即终止当前音效（告警打断 TTS / 服务销毁时用）。 */
    fun stop() {
        mainHandler.post { releaseActive() }
    }

    fun release() {
        mainHandler.post { releaseActive() }
    }

    private fun releaseActive() {
        active?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        active = null
        activeRoute = null
        activeDone = null
    }

    private fun finish(onDone: (Result) -> Unit, r: Result) = onDone(r)

    /** 媒体音量是否被拉到 0：真机"没声音"最常见的原因，用于给主控端回明确提示。 */
    fun isMediaVolumeMuted(): Boolean =
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        }.getOrDefault(false)
}
