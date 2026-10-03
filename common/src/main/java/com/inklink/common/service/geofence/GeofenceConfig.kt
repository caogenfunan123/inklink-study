package com.inklink.common.service.geofence

/**
 * 围栏配置载荷（GEOFENCE_CONFIG）。
 *
 * 向后兼容设计：
 * - 旧字段 [lat]/[lng]/[radius] 始终写主围栏（第 0 个），旧受控端只解析这三个字段
 * - 新增可选 [fences] 列表承载全部围栏，旧端 Gson 自动忽略未知字段
 */
data class GeofenceConfig(
    val lat: Double,
    val lng: Double,
    val radius: Double,
    val fences: List<FenceEntry>? = null
) {

    data class FenceEntry(
        val lat: Double,
        val lng: Double,
        val radius: Double,
        val name: String? = null
    )

    fun toGeoFence(): GeoFence = GeoFence(lat, lng, radius)

    /** 展开为受控端判定用的围栏列表：优先 fences 数组，缺失时回退主围栏字段。 */
    fun toGeoFences(): List<GeoFence> =
        fences?.takeIf { it.isNotEmpty() }
            ?.map { GeoFence(it.lat, it.lng, it.radius, it.name) }
            ?: listOf(GeoFence(lat, lng, radius))

    companion object {
        fun from(fence: GeoFence): GeofenceConfig =
            GeofenceConfig(fence.latitude, fence.longitude, fence.radiusMeters)

        /** 从围栏列表构造协议载荷：主围栏写旧字段，全部写 fences 数组。 */
        fun fromList(fences: List<GeoFence>): GeofenceConfig {
            require(fences.isNotEmpty()) { "围栏列表不能为空" }
            val primary = fences.first()
            return GeofenceConfig(
                lat = primary.latitude,
                lng = primary.longitude,
                radius = primary.radiusMeters,
                fences = fences.map {
                    FenceEntry(it.latitude, it.longitude, it.radiusMeters, it.name)
                }
            )
        }
    }
}
