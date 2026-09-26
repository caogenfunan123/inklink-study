package com.inklink.host.state

/**
 * 全局活跃时钟（V1.1 双时钟规则裁决 #1）。
 *
 * 前台：全速秒级衰减（玩家手感"每秒可见"）。
 * 后台/灭屏：所有衰减速率 ÷20，且属性有地板 30（离屏不会跌破 30，
 * 解决"一节课宠物死透"的劝退问题）。
 *
 * 由 PetMainActivity 在 onResume/onPause 翻转；翻转前必须先 settle 一次，
 * 保证已流逝时间按当时状态计价（否则混叠区间会被整段按同一倍率结算）。
 */
object PetClock {

    /** 后台减速倍率 */
    const val IDLE_FACTOR = 20.0

    /** 后台衰减地板（不会因离屏跌破此值） */
    const val FLOOR = 30

    /** Buff 赠送后对应属性衰减暂停时长：10 分钟（裁决 #2） */
    const val BUFF_PAUSE_MS = 10 * 60 * 1000L

    @Volatile
    var foreground: Boolean = false

    /** 当前结算倍率：前台 1.0，后台 IDLE_FACTOR */
    fun factor(): Double = if (foreground) 1.0 else IDLE_FACTOR

    /** 是否处于前台（含屏幕点亮）；Room/持久化侧共用此判定做节流窗口。 */
    fun isForeground(): Boolean = foreground
}
