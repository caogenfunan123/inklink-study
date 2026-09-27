package com.inklink.controller.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 受控端设备列表本地存储（SharedPreferences + JSON）。
 *
 * 主控端可绑定多台受控端，列表按 [DeviceEntity.deviceId] 去重；
 * 增删改均立即落盘，重启后保持。
 */
class DeviceRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun list(): List<DeviceEntity> {
        val json = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<DeviceEntity>>(json, listType)
        }.getOrNull() ?: emptyList()
    }

    fun findByDeviceId(deviceId: String): DeviceEntity? =
        list().firstOrNull { it.deviceId == deviceId }

    /** 添加设备；已存在相同 deviceId 时更新昵称。 */
    fun add(device: DeviceEntity) {
        val devices = list().toMutableList()
        val existing = devices.indexOfFirst { it.deviceId == device.deviceId }
        if (existing >= 0) {
            devices[existing] = device
        } else {
            devices.add(device)
        }
        save(devices)
    }

    fun remove(deviceId: String) {
        save(list().filterNot { it.deviceId == deviceId })
    }

    fun updateNickname(deviceId: String, nickname: String) {
        save(list().map { if (it.deviceId == deviceId) it.copy(nickname = nickname) else it })
    }

    /** 记录设备最后一次下发的围栏（WGS-84），供主控端地图画圈可视化。 */
    fun updateFence(deviceId: String, lat: Double, lng: Double, radiusMeters: Double) {
        save(list().map {
            if (it.deviceId == deviceId) {
                it.copy(fenceLat = lat, fenceLng = lng, fenceRadiusM = radiusMeters)
            } else {
                it
            }
        })
    }

    private fun save(devices: List<DeviceEntity>) {
        prefs.edit().putString(KEY_DEVICES, gson.toJson(devices)).apply()
    }

    companion object {
        private const val PREFS_NAME = "inklink_devices"
        private const val KEY_DEVICES = "devices"

        private val listType = object : TypeToken<List<DeviceEntity>>() {}.type
    }
}
