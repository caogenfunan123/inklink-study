package com.inklink.common.service.gps

/**
 * 增强 GPS 定位上报载荷。
 *
 * 坐标全链路保持 WGS-84 原始坐标系，仅在主控端调用外部 WebService 路线规划时单向转换。
 */
data class GpsReport(
    val lat: Double,
    val lng: Double,
    val speed: Float = 0f,
    val time: Long = System.currentTimeMillis(),
    val accuracy: Float = 0f,
    val altitude: Double = 0.0,
    val bearing: Float = 0f,
    val provider: String = "gps",
    val quality: String = "HIGH", // HIGH (<15m), MEDIUM (15-50m), LOW (>50m), NO_GPS
    val satelliteCount: Int = 0
)
