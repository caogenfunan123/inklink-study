package com.inklink.common.service.geofence

import kotlin.math.atan2
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
 * 本地电子围栏管理器。
 *
 * 基于 Haversine 距离计算判定进出（避免依赖 Google Geofencing API，兼容国产设备）。
 * 带去抖逻辑：状态翻转需连续 [confirmCount] 次采样确认，防止 GPS 抖动触发重复告警。
 */
class GeoFenceManager(
    private val confirmCount: Int = 2
) {

    private var fence: GeoFence? = null
    private var inside: Boolean = false
    private var pendingState: Boolean? = null
    private var pendingHits: Int = 0

    /** 更新围栏配置，重置去抖状态。 */
    fun updateFence(fence: GeoFence?) {
        this.fence = fence
        this.inside = false
        resetPending()
    }

    fun currentFence(): GeoFence? = fence

    fun isInside(): Boolean = inside

    /**
     * 输入一次定位，返回本次采样触发的进出事件（去抖确认后），无事件返回 null。
     */
    fun onLocation(latitude: Double, longitude: Double): FenceEvent? {
        val f = fence ?: return null
        val nowInside = distanceMeters(latitude, longitude, f.latitude, f.longitude) <= f.radiusMeters

        if (nowInside == inside) {
            resetPending()
            return null
        }

        if (pendingState != nowInside) {
            pendingState = nowInside
            pendingHits = 1
        } else {
            pendingHits++
        }

        if (pendingHits >= confirmCount) {
            inside = nowInside
            resetPending()
            return if (nowInside) FenceEvent.Enter(f) else FenceEvent.Exit(f)
        }
        return null
    }

    private fun resetPending() {
        pendingState = null
        pendingHits = 0
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6371000.0

        /** Haversine 距离（米）。 */
        fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return EARTH_RADIUS_METERS * c
        }
    }
}
