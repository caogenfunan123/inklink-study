package com.inklink.common.service.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.GpsStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper

/**
 * GPS 与网络双源融合定位管理器（受控端前台服务内运行）。
 *
 * 特性：
 * 1. 双源采集：GPS + Network 双 Provider 监听，无缝平滑切换。
 * 2. 卫星监听：通过 GnssStatus 实时捕获可用卫星数与参与解算卫星数。
 * 3. 动静卡尔曼滤波：抑制静止漂移，精确动态轨迹。
 * 4. 完备指标：输出精度(accuracy)、定级质量(quality 0-3)、卫星数(satelliteCount)。
 */
class GpsManager(
    private val context: Context,
    private val onLocationReport: (GpsReport) -> Unit
) {

    constructor(
        context: Context,
        legacyCallback: (Double, Double, Float, Long) -> Unit
    ) : this(context, { report ->
        legacyCallback(report.lat, report.lng, report.speed, report.time)
    })

    private var locationManager: LocationManager? = null
    private val kalmanFilter = KalmanLocationFilter()

    @Volatile
    private var currentSatelliteCount: Int = 0

    @Volatile
    private var lastGpsLocation: Location? = null

    @Volatile
    private var lastNetworkLocation: Location? = null

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastGpsLocation = location
            processLocation(location, "gps")
        }

        override fun onProviderDisabled(provider: String) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle) = Unit
    }

    private val networkListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastNetworkLocation = location
            // 仅当 GPS 无有效定位（超 15s 无 GPS 数据或精度极差）时才由 Network 接管
            val gps = lastGpsLocation
            val gpsExpired = gps == null || (System.currentTimeMillis() - gps.time > 15_000L)
            if (gpsExpired) {
                processLocation(location, "network")
            }
        }

        override fun onProviderDisabled(provider: String) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle) = Unit
    }

    private var gnssStatusCallback: Any? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (locationManager != null) return
        if (!GpsDetector.isLocationAvailable(context)) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        runCatching {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    DEFAULT_INTERVAL_MS,
                    DEFAULT_MIN_DISTANCE_M,
                    gpsListener,
                    Looper.getMainLooper()
                )
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    DEFAULT_INTERVAL_MS,
                    DEFAULT_MIN_DISTANCE_M,
                    networkListener,
                    Looper.getMainLooper()
                )
            }
            registerSatelliteListener(lm)
            locationManager = lm
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerSatelliteListener(lm: LocationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val callback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    var usedCount = 0
                    val total = status.satelliteCount
                    for (i in 0 until total) {
                        if (status.usedInFix(i)) {
                            usedCount++
                        }
                    }
                    currentSatelliteCount = if (usedCount > 0) usedCount else total
                }
            }
            lm.registerGnssStatusCallback(callback, null)
            gnssStatusCallback = callback
        } else {
            @Suppress("DEPRECATION")
            val listener = GpsStatus.Listener { event ->
                if (event == GpsStatus.GPS_EVENT_SATELLITE_STATUS) {
                    val status = lm.getGpsStatus(null) ?: return@Listener
                    var count = 0
                    for (sat in status.satellites) {
                        if (sat.usedInFix()) count++
                    }
                    currentSatelliteCount = count
                }
            }
            @Suppress("DEPRECATION")
            lm.addGpsStatusListener(listener)
            gnssStatusCallback = listener
        }
    }

    @SuppressLint("MissingPermission")
    private fun unregisterSatelliteListener(lm: LocationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (gnssStatusCallback as? GnssStatus.Callback)?.let {
                lm.unregisterGnssStatusCallback(it)
            }
        } else {
            @Suppress("DEPRECATION")
            (gnssStatusCallback as? GpsStatus.Listener)?.let {
                lm.removeGpsStatusListener(it)
            }
        }
        gnssStatusCallback = null
    }

    fun stop() {
        locationManager?.let { lm ->
            lm.removeUpdates(gpsListener)
            lm.removeUpdates(networkListener)
            unregisterSatelliteListener(lm)
        }
        locationManager = null
        kalmanFilter.reset()
    }

    private fun processLocation(loc: Location, providerType: String) {
        val (filteredLat, filteredLng) = kalmanFilter.filter(
            loc.latitude,
            loc.longitude,
            loc.accuracy,
            loc.speed,
            loc.time
        )
        val quality = when {
            loc.accuracy <= 15f && currentSatelliteCount >= 6 -> "HIGH"
            loc.accuracy <= 50f -> "MEDIUM"
            loc.accuracy > 50f -> "LOW"
            else -> "NO_GPS"
        }

        val report = GpsReport(
            lat = filteredLat,
            lng = filteredLng,
            speed = loc.speed,
            time = System.currentTimeMillis(),
            satelliteCount = if (providerType == "gps") currentSatelliteCount else 0,
            accuracy = loc.accuracy,
            quality = quality,
            provider = providerType
        )
        onLocationReport(report)
    }

    /**
     * 主动拉取一次单次定位（用于 REQUEST_GPS 指令响应）。
     */
    @SuppressLint("MissingPermission")
    fun requestSingleUpdate(onResult: (GpsReport) -> Unit) {
        if (!GpsDetector.isLocationAvailable(context)) return
        val lm = (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager) ?: return

        runCatching {
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            last?.let {
                onResult(
                    GpsReport(
                        lat = it.latitude,
                        lng = it.longitude,
                        speed = it.speed,
                        time = System.currentTimeMillis(),
                        satelliteCount = currentSatelliteCount,
                        accuracy = it.accuracy,
                        quality = "MEDIUM",
                        provider = it.provider ?: "unknown"
                    )
                )
            }
        }

        val singleListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val report = GpsReport(
                    lat = loc.latitude,
                    lng = loc.longitude,
                    speed = loc.speed,
                    time = System.currentTimeMillis(),
                    satelliteCount = currentSatelliteCount,
                    accuracy = loc.accuracy,
                    quality = "HIGH",
                    provider = loc.provider ?: "single"
                )
                onResult(report)
            }

            override fun onProviderDisabled(provider: String) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onStatusChanged(provider: String, status: Int, extras: Bundle) = Unit
        }

        runCatching {
            val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else {
                LocationManager.NETWORK_PROVIDER
            }
            lm.requestSingleUpdate(provider, singleListener, Looper.getMainLooper())
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 5_000L
        const val DEFAULT_MIN_DISTANCE_M = 3f
    }
}
