package com.inklink.controller.data

/**
 * 受控端设备条目（主控端本地持久化）。
 *
 * [deviceId] 为受控端唯一标识（受控端「连接信息」中展示的「设备 ID」），
 * [nickname] 为用户自定义备注，便于在多台设备间识别。
 */
data class DeviceEntity(
    val deviceId: String,
    val nickname: String
)
