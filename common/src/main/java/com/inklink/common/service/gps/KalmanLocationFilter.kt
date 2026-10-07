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

    /** 上一帧速度，用于检测「静止 → 起步」跃迁（见 filter 的突变恢复）。 */
    private var prevSpeed: Float = -1f

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
            prevSpeed = speed
            return lat to lng
        }

        // 起步突变恢复：长时间静止使方差收敛到 ~0.05㎡，卡尔曼增益 k≈0.0005，
        // 每帧只追 5mm——从静止切换为步行后要上千次更新（分钟级）才追上真实位置，
        // 期间围栏进出判定与寻宝累计全部滞后。检测到速度跃迁立即把协方差放开到
        // 当前观测噪声量级（k≈0.5），数帧内收敛。
        if (prevSpeed in 0f..STATIONARY_SPEED && speed > MOVING_SPEED) {
            variance = r
        }
        prevSpeed = speed

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
        prevSpeed = -1f
    }

    companion object {
        private const val DEFAULT_R = 100.0 // 默认测量噪声

        /** 静止判定速度（m/s）：与 GpsManager 的静止抑制口径一致。 */
        private const val STATIONARY_SPEED = 0.5f

        /** 起步判定速度（m/s）：明显高于静止阈值，避免把 GPS 噪声当成起步。 */
        private const val MOVING_SPEED = 1.5f
    }
}
