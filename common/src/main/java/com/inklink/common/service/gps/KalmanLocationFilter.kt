package com.inklink.common.service.gps

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 2D 动静自适应卡尔曼滤波（针对经纬度与速度）。
 *
 * 在低速/静止状态（speed < 0.5m/s）下收敛方差，抑制 GPS 室内与多径效应引起的经纬度漂移；
 * 在运动状态下自适应放宽过程噪声，避免轨迹滞后。
 */
class KalmanLocationFilter {

    private var lat: Double = 0.0
    private var lng: Double = 0.0
    private var variance: Double = -1.0 // 协方差，-1 表示未初始化
    private var lastTimestamp: Long = 0L

    fun filter(
        rawLat: Double,
        rawLng: Double,
        accuracy: Float,
        speed: Float,
        timestamp: Long
    ): Pair<Double, Double> {
        val r = if (accuracy > 0) accuracy.toDouble() * accuracy.toDouble() else DEFAULT_R

        if (variance < 0) {
            // 初次初始化
            lat = rawLat
            lng = rawLng
            variance = r
            lastTimestamp = timestamp
            return lat to lng
        }

        val dt = (timestamp - lastTimestamp).coerceAtLeast(0) / 1000.0
        lastTimestamp = timestamp

        // 根据速度动态调整过程噪声 Q (m^2/s)
        val q = when {
            speed < 0.5f -> 0.05 // 静止状态，强抑制
            speed < 3.0f -> 1.0  // 步行状态
            else -> 5.0          // 车载/快速运动状态
        }

        // 状态外推
        variance += dt * q

        // 测量更新
        val k = variance / (variance + r)
        lat += k * (rawLat - lat)
        lng += k * (rawLng - lng)
        variance = (1.0 - k) * variance

        return lat to lng
    }

    fun reset() {
        variance = -1.0
    }

    companion object {
        private const val DEFAULT_R = 100.0 // 默认测量噪声
    }
}
