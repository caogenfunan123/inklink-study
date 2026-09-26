package com.inklink.common.utils

import java.util.LinkedHashMap

/**
 * 消息幂等控制器。
 *
 * 维护容量为 [maxCapacity] 的环形 LinkedHashMap，超时 [ttlMs] 自动失效。
 * 收到重复 msgId 时判定为已处理，防止指令多次重复执行（如重复触发蜂鸣响铃）。
 *
 * ## 时钟：[timeMs] 必须与"同一条消息的两次投递"处于同一时基
 *
 * 默认 [MonoClock]（单调、含深睡）。早期版本用 `System.currentTimeMillis()`，
 * 而墙钟在儿童手表上会往回跳（家长 App 设时间/时区/NTP 校正），此时
 * `now - existTime` 为负 → 恒 `< ttlMs` → **条目永不过期**，又因 `cleanExpired`
 * 同样判不出来而清不掉，结果是同一条 msgId 之后的指令被永久吞掉、日志干净、重启才"自愈"。
 * 这是本类唯一的功能性风险点，已用 [checkAndRecord] 的时钟异常分支 + 单测钉死。
 *
 * 时钟异常（返回更早的值）时按**已过期**处理：宁可重复执行一次（网络重投本就可能双发，
 * 上层还有语义守卫），也不能让指令永久失效。
 *
 * @param timeMs 时钟源（同域毫秒）。单测注入假时钟以覆盖 TTL 边界与容量淘汰。
 */
class IdempotentController(
    private val maxCapacity: Int = 50,
    private val ttlMs: Long = 60_000L,
    private val timeMs: () -> Long = { MonoClock.now() }
) {
    private val cache = object : LinkedHashMap<String, Long>(maxCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > maxCapacity
        }
    }

    private val lock = Any()

    /**
     * 检查并记录消息。
     * @return 若为新消息返回 true；若为重复消息返回 false。
     */
    fun checkAndRecord(msgId: String?): Boolean {
        if (msgId.isNullOrBlank()) return true
        val now = timeMs()
        synchronized(lock) {
            cleanExpired(now)
            val existTime = cache[msgId]
            if (existTime != null) {
                val age = now - existTime
                // age < 0 = 时钟异常（不应发生），按过期放行，避免条目不死
                if (age in 0 until ttlMs) {
                    return false
                }
            }
            cache[msgId] = now
            return true
        }
    }

    private fun cleanExpired(now: Long) {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val age = now - entry.value
            if (age < 0 || age >= ttlMs) {
                iterator.remove()
            }
        }
    }

    /** 当前缓存条数（仅供测试与诊断使用）。 */
    fun size(): Int = synchronized(lock) { cache.size }

    fun clear() {
        synchronized(lock) { cache.clear() }
    }
}
