package com.inklink.host.state

import com.inklink.common.service.geofence.GeoFenceManager
import com.inklink.common.service.gps.GpsReport
import com.inklink.common.utils.MonoClock

/**
 * GPS 移动寻宝计算器 (80 米位移阈值 + 60 秒冷却)
 */
class GpsTreasureHunter(
    private val onTreasureFound: (coinGain: Int, expGain: Int) -> Unit
) {
    private var lastLat: Double? = null
    private var lastLng: Double? = null

    /** 单调时钟：墙钟回跳会让冷却恒「未到」或瞬间过期，两者都错 */
    private var lastRewardTs: Long = Long.MIN_VALUE

    fun onLocationUpdate(report: GpsReport) {
        val now = MonoClock.now()
        if (lastRewardTs != Long.MIN_VALUE && now - lastRewardTs < 60_000L) {
            return // 60s 冷却中
        }

        val prevLat = lastLat
        val prevLng = lastLng
        if (prevLat == null || prevLng == null) {
            lastLat = report.lat
            lastLng = report.lng
            return
        }

        val dist = GeoFenceManager.distanceMeters(prevLat, prevLng, report.lat, report.lng)
        if (dist >= 80.0) {
            lastLat = report.lat
            lastLng = report.lng
            lastRewardTs = now
            val coin = (dist / 20.0).toInt().coerceIn(5, 20)
            val exp = (dist / 10.0).toInt().coerceIn(10, 30)
            onTreasureFound(coin, exp)
        }
    }
}
