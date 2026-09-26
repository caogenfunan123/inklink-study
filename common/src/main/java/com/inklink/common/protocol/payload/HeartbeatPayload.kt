package com.inklink.common.protocol.payload

/**
 * 极简保活心跳（HEARTBEAT, type=99）。
 */
data class HeartbeatPayload(
    val seq: Long = 0,
    val batteryPct: Int = 100,
    val isAlive: Boolean = true
)
