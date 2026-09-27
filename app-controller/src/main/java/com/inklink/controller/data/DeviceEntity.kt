package com.inklink.controller.data

/**
 * 受控端设备条目（主控端本地持久化）。
 *
 * [deviceId] 为受控端唯一标识（受控端「连接信息」中展示的「设备 ID」），
 * [nickname] 为用户自定义备注，便于在多台设备间识别。
 *
 * 围栏三字段记录最后一次成功下发到该受控端的围栏（WGS-84 坐标），
 * 供主控端地图可视化（未下发过或旧数据均为 null，Gson 自动兼容）。
 */
data class DeviceEntity(
    val deviceId: String,
    val nickname: String,
    val fenceLat: Double? = null,
    val fenceLng: Double? = null,
    val fenceRadiusM: Double? = null
)
