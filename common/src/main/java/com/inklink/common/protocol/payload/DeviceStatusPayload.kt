package com.inklink.common.protocol.payload

/**
 * 受控端设备状态节流上报（DEVICE_STATUS_REPORT, type=18）。
 */
data class DeviceStatusPayload(
    val batteryPct: Int = 100,
    val isCharging: Boolean = false,
    val netType: String = "4G",
    val signalDbm: Int = -80,
    val batteryOptimized: Boolean = false, // true 表示系统电池优化依然开启（后台受限）
    val locationPermission: Boolean = true,
    val audioPermission: Boolean = true,
    val isRinging: Boolean = false,
    val memFreeMb: Long = 0,
    val appVersion: String = "1.0.0",
    /**
     * 受控端是否具备文字播报能力（《音频系统 Final-Rev1》§七.3）。
     * 三态用「默认 true + 显式 false」表达会有歧义（未知≠支持），故用可空：
     * null = 引擎初始化尚未落定（老端反序列化为 null，按不提示处理）。
     */
    val ttsAvailable: Boolean? = null
)
