package com.inklink.common.service.geofence

/**
 * 电子围栏数据模型。圆形围栏：圆心 + 半径（米）。
 */
data class GeoFence(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double
) {
    init {
        require(radiusMeters > 0) { "围栏半径必须大于 0" }
    }
}
