package com.inklink.common.service.geofence

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 围栏进出判定事件。
 */
sealed class FenceEvent {
    data class Enter(val fence: GeoFence) : FenceEvent()
    data class Exit(val fence: GeoFence) : FenceEvent()
}

/**
 * 本地电子围栏管理器（支持多围栏）。
 *
 * 基于 Haversine 距离计算判定进出（避免依赖 Google Geofencing API，兼容国产设备）。
 * 每个围栏独立维护进出状态与去抖计数：状态翻转需连续 [confirmCount] 次采样确认，
 * 防止 GPS 抖动触发重复告警。
 */
class GeoFenceManager(
    private val confirmCount: Int = 2
) {

    private class FenceState(val fence: GeoFence) {
        var inside = false
        var pendingState: Boolean? = null
        var pendingHits = 0

        fun resetPending() {
            pendingState = null
            pendingHits = 0
        }
    }

    private var states: List<FenceState> = emptyList()

    /** 更新围栏配置（多围栏），重置去抖状态。 */
    fun updateFences(fences: List<GeoFence>) {
        states = fences.map { FenceState(it) }
    }

    /** 单围栏兼容入口。传 null 清空围栏。 */
    fun updateFence(fence: GeoFence?) {
        updateFences(fence?.let { listOf(it) } ?: emptyList())
    }

    fun currentFences(): List<GeoFence> = states.map { it.fence }

    fun currentFence(): GeoFence? = states.firstOrNull()?.fence

    /** 是否处于任一围栏内。 */
    fun isInside(): Boolean = states.any { it.inside }

    /**
     * 输入一次定位，返回本次采样触发的全部进出事件（去抖确认后），无事件返回空列表。
     */
    fun onLocation(latitude: Double, longitude: Double): List<FenceEvent> {
        val events = mutableListOf<FenceEvent>()
        for (state in states) {
            val f = state.fence
            val nowInside = distanceMeters(latitude, longitude, f.latitude, f.longitude) <= f.radiusMeters

            if (nowInside == state.inside) {
                state.resetPending()
                continue
            }

            if (state.pendingState != nowInside) {
                state.pendingState = nowInside
                state.pendingHits = 1
            } else {
                state.pendingHits++
            }

            if (state.pendingHits >= confirmCount) {
                state.inside = nowInside
                state.resetPending()
                events.add(if (nowInside) FenceEvent.Enter(f) else FenceEvent.Exit(f))
            }
        }
        return events
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6371000.0

        /** Haversine 距离（米）。 */
        fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = (sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)).coerceIn(0.0, 1.0)
            // 对径点等浮点越界场景 a 可能微大于 1，钳制后 asin 不会产出 NaN
            val c = 2 * asin(sqrt(a))
            return EARTH_RADIUS_METERS * c
        }
    }
}
