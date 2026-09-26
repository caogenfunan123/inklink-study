package com.inklink.common.utils

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 上报节流器：抑制过度上报，节省网络流量与电量。
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
     */
    fun shouldReportLocation(lat: Double, lng: Double, now: Long = System.currentTimeMillis()): Boolean {
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
     */
    fun shouldReportStatus(batteryPct: Int, isCharging: Boolean, now: Long = System.currentTimeMillis()): Boolean {
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
        fun calculateDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6371000.0 // 地球平均半径（米）
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLng / 2) * sin(dLng / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return r * c
        }
    }
}
