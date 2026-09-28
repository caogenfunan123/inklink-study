package com.inklink.host.service

import android.content.Context

/**
 * 受控端围栏配置持久化。
 *
 * GeoFenceManager 仅存内存，服务/进程重启即丢；本类把最近一次下发的
 * GEOFENCE_CONFIG 原始 JSON 落 SharedPreferences，服务启动时恢复，避免
 * 「下发了围栏但重启孩子端后失效」。
 */
class GeofenceStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(payloadJson: String) {
        prefs.edit().putString(KEY_FENCE, payloadJson).apply()
    }

    fun load(): String? = prefs.getString(KEY_FENCE, null)

    companion object {
        private const val PREFS_NAME = "inklink_geofence"
        private const val KEY_FENCE = "fence_json"
    }
}
