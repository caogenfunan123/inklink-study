package com.inklink.common.service.gps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * GPS 能力检测。判断设备硬件与权限是否支持定位。
 */
object GpsDetector {

    /** 是否具备定位硬件（GPS 或网络定位 provider）。 */
    fun hasLocationHardware(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.allProviders.contains(LocationManager.GPS_PROVIDER) ||
            lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)
    }

    /** 是否已授予定位权限。 */
    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /** 定位模块整体是否可用（硬件 + 权限同时满足）。 */
    fun isLocationAvailable(context: Context): Boolean =
        hasLocationHardware(context) && hasLocationPermission(context)
}
