package com.inklink.common.service.geofence

/**
 * 电子围栏（WGS-84 坐标）。
 *
 * [name] 为可选展示名（如「家」「学校」），协议序列化时旧端自动忽略未知字段。
 */
data class GeoFence(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val name: String? = null
) {
    init {
        require(radiusMeters > 0) { "围栏半径必须大于 0" }
    }
}
