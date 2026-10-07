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

    /**
     * 校验版展开：任一条目非法（半径 ≤0/非有限、经纬度越界/非有限）返回 null。
     *
     * 对端（主控端）不可信：Gson 不走 Kotlin 构造默认值，缺 radius 的载荷会反序列化出
     * 0.0，直接触发 [GeoFence] 的 require 抛异常——该异常发生在传输回调线程上，
     * 会打死整条命令链（后续所有指令静默失效）。畸形载荷必须被拒绝而不是被信任。
     */
    fun toGeoFencesOrNull(): List<GeoFence>? {
        fun valid(lat: Double, lng: Double, radius: Double): Boolean =
            radius > 0 && lat.isFinite() && lng.isFinite() &&
                lat in -90.0..90.0 && lng in -180.0..180.0
        val entries = fences?.takeIf { it.isNotEmpty() }
        if (entries == null) {
            return if (valid(lat, lng, radius)) listOf(GeoFence(lat, lng, radius)) else null
        }
        val built = ArrayList<GeoFence>(entries.size)
        for (e in entries) {
            if (!valid(e.lat, e.lng, e.radius)) return null
            built.add(GeoFence(e.lat, e.lng, e.radius, e.name))
        }
        return built
    }

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
