package com.inklink.controller.data

import com.inklink.common.service.geofence.GeofenceConfig

/**
 * 受控端设备条目（主控端本地持久化）。
 *
 * [deviceId] 为受控端唯一标识（受控端「连接信息」中展示的「设备 ID」），
 * [nickname] 为用户自定义备注，便于在多台设备间识别。
 *
 * 围栏三字段记录最后一次成功下发到该受控端的主围栏（WGS-84 坐标，向后兼容旧数据），
 * [fences] 记录多围栏完整列表（为 null 时回退三字段），供主控端地图可视化。
 */
data class DeviceEntity(
    val deviceId: String,
    val nickname: String,
    val fenceLat: Double? = null,
    val fenceLng: Double? = null,
    val fenceRadiusM: Double? = null,
    val fences: List<GeofenceConfig.FenceEntry>? = null
)
