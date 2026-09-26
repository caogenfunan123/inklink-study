package com.inklink.common.protocol.payload

/**
 * 强制响铃指令载荷（CMD_RING, type=16）。
 */
data class RingPayload(
    val durationSec: Int = 15,
    val volumePct: Int = 100,
    val loopCount: Int = 5
)
