package com.inklink.common.utils

import android.os.SystemClock

/**
 * 单调时钟（[SystemClock.elapsedRealtime]：自开机计时，**含深睡**，不受用户改时间影响）。
 *
 * ## 为什么所有冷却/限流/去重窗口都必须用它，而不能用 [System.currentTimeMillis]
 *
 * 墙钟在儿童手表上是**会往回跳**的：家长 App 设置时间、时区变化、NTP 校正、孩子手动改。
 * 而冷却判据的形状都是 `now - last < window → 仍在窗口内`。一旦 `now < last`，差值恒为**负**，
 * 负数永远小于正窗口，于是：
 *
 * - 播报限流 → 「文字播报突然再也不响了，音效却一切正常」
 * - 饥饿冷却 → 「宠物饿死也不再提醒家长」
 * - 消息去重 → 条目变成不死身，**同一条指令永久被吞**，直到容量淘汰
 *
 * 共同特征：**功能整体静默失效、日志干净、重启即"自愈"**——最难排的一类。
 * 用它还有一个好处：深睡也计时，不会出现"睡了一夜回来窗口还差 3 秒没到"。
 */
object MonoClock {
    /** 毫秒。与 `elapsedRealtime()` 同域，只能与同域值相减。 */
    fun now(): Long = SystemClock.elapsedRealtime()
}

/**
 * 单调窗口节流器：`windowMs` 内只放行一次。用于"某个条件会**持续为真**、但只允许周期性动作"
 * 的场合（播报限流、告警冷却）。与 [IdempotentController]（按 key 去重、每个 key 一份窗口）互补。
 *
 * 与既有 [ReportThrottler] 的区别：后者语义是"位移/电量变化才上报"，绑死了经纬度与百分比，
 * 不能复用成纯时间窗口；[HeartbeatPolicy] 只回答"间隔该多长"，不持有上次时刻。
 *
 * 时钟通过 [timeMs] 注入以便 JVM 单测；生产默认 [MonoClock]。
 *
 * 线程安全：状态只在 [synchronized] 内读写。
 */
class MonoThrottle(
    private val windowMs: Long,
    private val timeMs: () -> Long = { MonoClock.now() }
) {
    private val lock = Any()
    private var lastPassTs = Long.MIN_VALUE

    /** 最近一次被拒绝的时刻（同域毫秒），null = 从未拒绝。进日志便于真机排障。 */
    @Volatile
    var lastRejectTs: Long? = null
        private set

    /**
     * 尝试放行。首次调用必放行。
     *
     * 时钟异常（[timeMs] 返回了比上次更早的值）按**窗口已过**处理，即放行。
     * 取舍：对互动类动作，"多做一次"的代价远小于"永久失效"——前者是孩子多吃一颗糖，
     * 后者是功能整体静默死亡且没有任何错误日志。默认时钟单调，此分支在生产中不可达，
     * 但它把"注入一个坏时钟"的后果钉死成不会永久卡住（有回归测试）。
     *
     * @return true = 放行；false = 仍在窗口内，应跳过
     */
    fun tryAcquire(): Boolean {
        val now = timeMs()
        synchronized(lock) {
            if (windowMs > 0 && lastPassTs != Long.MIN_VALUE) {
                val delta = now - lastPassTs
                if (delta in 0 until windowMs) {
                    lastRejectTs = now
                    return false
                }
            }
            lastPassTs = now
            lastRejectTs = null
            return true
        }
    }

    /** 距窗口结束的剩余毫秒；已放行过且窗口已过返回 0。UI 冷却倒计时可读它（不参与判据）。 */
    fun remainingMs(): Long {
        val last = synchronized(lock) { lastPassTs }
        if (last == Long.MIN_VALUE || windowMs <= 0) return 0
        return (windowMs - (timeMs() - last)).coerceAtLeast(0L)
    }

    /** 清空窗口（如宠物被重新激活、配对关系变更时不希望继承上一条的冷却）。 */
    fun reset() {
        synchronized(lock) { lastPassTs = Long.MIN_VALUE }
    }
}
