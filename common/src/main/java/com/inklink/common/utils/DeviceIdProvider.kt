package com.inklink.common.utils

import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * 设备唯一标识提供器。
 *
 * 策略：
 * 1. 优先读取 [Settings.Secure.ANDROID_ID]（无需权限）。
 * 2. 当 ANDROID_ID 读取失败、为空、为已知无效默认值、或疑似重复时，
 *    生成 UUID 并持久化到 SharedPreferences 作为兜底，保证重启后稳定。
 *
 * 背景：Android 6.0 恢复出厂会重置 ANDROID_ID；部分无 Google 服务的手表
 * 会返回固定相同值（如 "9774d56d682e549c"）。
 */
object DeviceIdProvider {

    private const val PREFS_NAME = "inklink_device"
    private const val KEY_DEVICE_ID = "device_id"

    /** ANDROID_ID 已知无效默认值，出现时视为不可靠。 */
    private val INVALID_ANDROID_IDS = setOf(
        "9774d56d682e549c", // 无 GMS 设备常见固定值
        ""
    )

    @Volatile
    private var cached: String? = null

    fun getDeviceId(context: Context): String {
        cached?.let { return it }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val persisted = prefs.getString(KEY_DEVICE_ID, null)
        if (!persisted.isNullOrBlank()) {
            cached = persisted
            return persisted
        }

        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()

        val resolved = if (!androidId.isNullOrBlank() && androidId !in INVALID_ANDROID_IDS) {
            androidId
        } else {
            UUID.randomUUID().toString()
        }

        prefs.edit().putString(KEY_DEVICE_ID, resolved).apply()
        cached = resolved
        return resolved
    }
}
