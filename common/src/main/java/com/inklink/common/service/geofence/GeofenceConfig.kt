package com.inklink.common.service.geofence

/**
 * 围栏配置载荷（GEOFENCE_CONFIG 消息的 payload，JSON 序列化）。
 *
 * 与 [GeoFence] 保持字段语义一致，但字段名为协议约定的 lat/lng/radius。
 */
data class GeofenceConfig(
    val lat: Double,
    val lng: Double,
    val radius: Double
) {
    fun toGeoFence(): GeoFence = GeoFence(lat, lng, radius)

    companion object {
        fun from(fence: GeoFence): GeofenceConfig =
            GeofenceConfig(fence.latitude, fence.longitude, fence.radiusMeters)
    }
}
