package com.inklink.host.state

import com.inklink.common.service.gps.GpsReport
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPS 移动寻宝计算器 (80 米位移阈值 + 60 秒冷却)
 */
class GpsTreasureHunter(
    private val onTreasureFound: (coinGain: Int, expGain: Int) -> Unit
) {
    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private var lastRewardTs: Long = 0L

    fun onLocationUpdate(report: GpsReport) {
        val now = System.currentTimeMillis()
        if (now - lastRewardTs < 60_000L) {
            return // 60s 冷却中
        }

        val prevLat = lastLat
        val prevLng = lastLng
        if (prevLat == null || prevLng == null) {
            lastLat = report.lat
            lastLng = report.lng
            return
        }

        val dist = calculateDistanceMeters(prevLat, prevLng, report.lat, report.lng)
        if (dist >= 80.0) {
            lastLat = report.lat
            lastLng = report.lng
            lastRewardTs = now
            val coin = (dist / 20.0).toInt().coerceIn(5, 20)
            val exp = (dist / 10.0).toInt().coerceIn(10, 30)
            onTreasureFound(coin, exp)
        }
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
