package com.inklink.host.pet

import com.inklink.common.utils.MonoClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 宠物 AI 行为树（V1.1 裁决：IDLE 8-20s 自主行为 + 状态抱怨 + 性格驱动倾向）。
 *
 * 决策优先级（Selector 语义，纯代码树无需重框架）：
 *   虚弱沉睡 > 睡眠 > 低数值抱怨(饿/困/脏/闷) > 性格加权自主小动作 > 待机呼吸
 * 抱怨类台词每类 60s 冷却防刷屏；自主动作出现频率受 playfulness 调制
 * （爱玩→蹦跳/挥手更多，冷落消沉→只剩眨眼叹气）。
 *
 * 结算数据零写入：仅读取数值快照决定表现，业务仍归 PetStateManager 唯一真相源。
 */
class PetAiEngine(
    private val scope: CoroutineScope,
    private val onAction: (action: AiAction) -> Unit
) {

    /** 行为指令：trigger=Rig 姿态名；line=情绪气泡台词（null 不弹） */
    data class AiAction(val trigger: String, val line: String? = null)

    /** UI 注入的数值快照读取器（引擎零业务依赖） */
    data class Snapshot(
        val hunger: Int,
        val energy: Int,
        val clean: Int,
        val happiness: Int,
        val alive: Boolean,
        val sleeping: Boolean,
        val playfulness: Int,
        val affection: Int,
        val isEgg: Boolean
    )

    var snapshotProvider: (() -> Snapshot)? = null
    var isSuppressed: Boolean = false // 报警或强交互时抑制空闲行为

    private var loopJob: Job? = null
    private val lastComplaintTs = HashMap<String, Long>()

    fun start() {
        stop()
        loopJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                // 8-20s 随机空闲判定（裁决规范区间）
                delay(8_000L + Random.nextLong(12_000L))
                if (!isSuppressed) decide()?.let { action -> onAction(action) }
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    private fun complaint(key: String, minGapMs: Long = 60_000L): Boolean {
        // 进程内冷却窗口走单调钟：墙钟回拨会让窗口恒负、台词刷屏
        val now = MonoClock.now()
        val last = lastComplaintTs[key] ?: 0L
        if (now - last < minGapMs) return false
        lastComplaintTs[key] = now
        return true
    }

    /** 一次决策：返回要执行的行为，或 null（本轮什么都不做）。 */
    private fun decide(): AiAction? {
        val s = snapshotProvider?.invoke() ?: return null

        // --- 高优先：状态驱动 ---
        if (s.isEgg) {
            // 蛋：偶尔晃动，暗示"快互动孵我"
            return if (complaint("EGG_WOBBLE", 20_000L)) AiAction("EGG_WOBBLE", "…咔哒") else null
        }
        if (!s.alive) {
            return if (complaint("WEAK", 45_000L)) AiAction("WEAK_GROAN", "好难受…需要治疗…") else null
        }
        if (s.sleeping) {
            // 睡眠偶尔说梦话（多数时候只有 Zzz 表现）
            return if (Random.nextInt(100) < 20 && complaint("SLEEP_TALK", 60_000L))
                AiAction("SLEEP_MUMBLE", "呼噜…呼呼…")
            else null
        }
        // 低数值抱怨（揉肚子/揉眼/挠身/发闷）
        if (s.hunger < 25 && complaint("HUNGRY")) {
            return AiAction("RUB_BELLY", "肚子饿啦…咕咕～")
        }
        if (s.energy < 20 && complaint("TIRED")) {
            return AiAction("RUB_EYES", "好想睡觉…哈～")
        }
        if (s.clean < 25 && complaint("DIRTY")) {
            return AiAction("SCRATCH", "身上脏脏的，痒痒…")
        }
        if (s.happiness < 25 && complaint("LONELY")) {
            val line = if (s.affection > 20) "陪陪我嘛…" else "哼，都不理我！"
            return AiAction("HEAD_LOW", line)
        }

        // --- 性格加权自主小动作 ---
        // playfulness 高 → 蹦跳/挥手占比升；低（被冷落消沉）→ 只剩眨眼/发呆/叹气
        val playful = (s.playfulness + 100) / 200f        // 0..1
        val roll = Random.nextInt(100)
        return when {
            roll < 20 + (playful * 20).toInt() -> AiAction("BLINK")
            roll < 45 -> AiAction("LOOK_AROUND")
            roll < 55 + (playful * 25).toInt() -> AiAction("HOP")
            roll < 70 + (playful * 15).toInt() -> AiAction("WAVE")
            roll < 80 -> AiAction("STRETCH")
            roll < 90 -> AiAction("YAWN")
            playful < 0.3f && complaint("SIGH", 90_000L) -> AiAction("SIGH", "唉…好无聊")
            else -> null
        }
    }
}
