package com.inklink.common.utils

import android.content.Context
import android.os.PowerManager

/**
 * 心跳间隔自适应策略。
 *
 * 亮屏状态业务活跃，用短间隔（10s）快速发现断连；灭屏状态为省电与后台保活，
 * 用长间隔（30s）降低唤醒频率。由调用方在每次心跳前查询当前间隔。
 */
object HeartbeatPolicy {

    const val SCREEN_ON_INTERVAL_MS = 10_000L
    const val SCREEN_OFF_INTERVAL_MS = 30_000L
    const val DEFAULT_INTERVAL_MS = 15_000L

    /** 根据当前屏幕状态返回下次心跳间隔。 */
    fun interval(context: Context): Long {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val interactive = pm?.isInteractive ?: true
        return if (interactive) SCREEN_ON_INTERVAL_MS else SCREEN_OFF_INTERVAL_MS
    }
}
