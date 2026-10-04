package com.inklink.host.state

/**
 * 全局活跃时钟（V1.1 双时钟规则裁决 #1；2026-08-31 "不玩默认休眠"修订）。
 *
 * 前台：全速秒级衰减（玩家手感"每秒可见"）。
 * 后台/灭屏：不再衰减，由 PetDecayEngine 按"自动休眠"语义结算——
 * 四维完全豁免、精力/健康按睡眠速率（速率为前台 1/IDLE_FACTOR）缓慢恢复，
 * 即"不玩就不掉状态"（废弃早期的 ÷20+地板 30 方案，FLOOR 常量已移除）。
 *
 * 由 PetMainActivity 在 onResume/onPause 翻转；翻转前必须先 settle 一次，
 * 保证已流逝时间按当时状态计价（否则混叠区间会被整段按同一倍率结算）。
 */
object PetClock {

    /** 后台/休眠结算减速倍率（恢复速率 = 前台 ÷ IDLE_FACTOR） */
    const val IDLE_FACTOR = 20.0

    /** Buff 赠送后对应属性衰减暂停时长：10 分钟（裁决 #2） */
    const val BUFF_PAUSE_MS = 10 * 60 * 1000L

    @Volatile
    var foreground: Boolean = false

    /** 当前结算倍率：前台 1.0，后台 1/IDLE_FACTOR */
    fun factor(): Double = if (foreground) 1.0 else 1.0 / IDLE_FACTOR

    /** 是否处于前台（含屏幕点亮）；Room/持久化侧共用此判定做节流窗口。 */
    fun isForeground(): Boolean = foreground
}
