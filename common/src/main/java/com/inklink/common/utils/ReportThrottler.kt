package com.inklink.common.utils

/**
 * 上报节流器：抑制过度上报，节省网络流量与电量。
 *
 * 时钟一律走 [MonoClock]（墙钟在儿童表上会回跳，`now - last` 恒负会让时间门静默失效）。
 */
class ReportThrottler(
    private val minLocationDistanceMeters: Double = 8.0,
    private val minLocationIntervalMs: Long = 3_000L,
    private val minBatteryChangePct: Int = 5,
    private val minStatusIntervalMs: Long = 30_000L
) {
    private var lastLocationTime = 0L
    private var lastLat = 0.0
    private var lastLng = 0.0

    private var lastBatteryPct = -1
    private var lastIsCharging: Boolean? = null
    private var lastStatusTime = 0L

    private val lock = Any()

    /**
     * 判定定位是否需要立即上报（位移 > 8m 或 超过 3s）。
     *
     * @param now 单调时钟毫秒，默认 [MonoClock.now]；测试可注入
     */
    fun shouldReportLocation(
        lat: Double,
        lng: Double,
        now: Long = MonoClock.now()
    ): Boolean {
        synchronized(lock) {
            if (lastLocationTime == 0L) {
                updateLocation(lat, lng, now)
                return true
            }
            val timeDiff = now - lastLocationTime
            val distance = calculateDistanceMeters(lastLat, lastLng, lat, lng)
            if (distance >= minLocationDistanceMeters || timeDiff >= minLocationIntervalMs) {
                updateLocation(lat, lng, now)
                return true
            }
            return false
        }
    }

    /**
     * 判定设备状态/电量是否需要上报（电量变化 ≥5%、充电状态改变、或超过 30s）。
     *
     * @param now 单调时钟毫秒，默认 [MonoClock.now]；测试可注入
     */
    fun shouldReportStatus(
        batteryPct: Int,
        isCharging: Boolean,
        now: Long = MonoClock.now()
    ): Boolean {
        synchronized(lock) {
            if (lastStatusTime == 0L || lastBatteryPct == -1) {
                updateStatus(batteryPct, isCharging, now)
                return true
            }
            val batteryDiff = kotlin.math.abs(batteryPct - lastBatteryPct)
            val chargingChanged = lastIsCharging != isCharging
            val timeDiff = now - lastStatusTime

            if (batteryDiff >= minBatteryChangePct || chargingChanged || timeDiff >= minStatusIntervalMs) {
                updateStatus(batteryPct, isCharging, now)
                return true
            }
            return false
        }
    }

    private fun updateLocation(lat: Double, lng: Double, time: Long) {
        lastLat = lat
        lastLng = lng
        lastLocationTime = time
    }

    private fun updateStatus(batteryPct: Int, isCharging: Boolean, time: Long) {
        lastBatteryPct = batteryPct
        lastIsCharging = isCharging
        lastStatusTime = time
    }

    companion object {
        /**
         * Haversine 距离（米）。唯一实现在 [com.inklink.common.service.geofence.GeoFenceManager]，
         * 此处委托以避免多份拷贝漂移（含对径点 NaN 修复）。
         */
        fun calculateDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double =
            com.inklink.common.service.geofence.GeoFenceManager.distanceMeters(lat1, lng1, lat2, lng2)
    }
}
