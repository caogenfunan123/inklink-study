package com.inklink.controller.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.inklink.common.utils.CoordinateConverter
import com.inklink.controller.BuildConfig
import com.inklink.controller.InkControllerApplication
import com.inklink.controller.R
import com.inklink.controller.service.RouteApi
import com.inklink.controller.state.ControllerState
import com.tencent.tencentmap.mapsdk.maps.CameraUpdateFactory
import com.tencent.tencentmap.mapsdk.maps.MapView
import com.tencent.tencentmap.mapsdk.maps.TencentMap
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import com.tencent.tencentmap.mapsdk.maps.model.Marker
import com.tencent.tencentmap.mapsdk.maps.model.MarkerOptions
import com.tencent.tencentmap.mapsdk.maps.model.Polyline
import com.tencent.tencentmap.mapsdk.maps.model.PolylineOptions

/**
 * 主控端地图页面：
 * - 主控端自身位置显示为绿色 Marker，受控端位置统一显示为红色 Marker。
 * - 每个受控端绘制历史轨迹折线。
 * - 支持主动拉取受控端位置、从自身位置到受控端规划驾车路线。
 */
class MapActivity : AppCompatActivity() {

    private lateinit var app: InkControllerApplication
    private lateinit var mapView: MapView
    private lateinit var tencentMap: TencentMap
    private lateinit var state: ControllerState
    private lateinit var tvInfo: TextView

    private val markers = mutableMapOf<String, Marker>()
    private val trajectoryPolylines = mutableMapOf<String, Polyline>()
    private var selfMarker: Marker? = null
    private var routePolyline: Polyline? = null

    /** 焦点设备（选中或最近上报）上一次位置，避免重复相机动画。 */
    private var lastFocusKey: String? = null
    private var lastFocusPos: Pair<Double, Double>? = null

    private val listener = object : ControllerState.Listener {
        override fun onStateChanged() = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        app = application as InkControllerApplication
        state = app.controllerState
        mapView = findViewById(R.id.map_view)
        tvInfo = findViewById(R.id.tv_info)

        tencentMap = mapView.map
        state.addListener(listener)

        findViewById<Button>(R.id.btn_refresh_gps).setOnClickListener {
            app.requestGps()
            Toast.makeText(this, R.string.refresh_gps, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_route).setOnClickListener { planRoute() }

        app.startSelfLocation()
        render()
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onDestroy() {
        state.removeListener(listener)
        mapView.onDestroy()
        super.onDestroy()
    }

    private fun render() {
        val gpsMap = state.gpsByDevice

        // 移除已不存在设备的 Marker 与轨迹线
        markers.keys.filter { it !in gpsMap }.forEach { id ->
            markers.remove(id)?.remove()
        }
        trajectoryPolylines.keys.filter { it !in gpsMap }.forEach { id ->
            trajectoryPolylines.remove(id)?.remove()
        }

        // 受控端红色 Marker + 历史轨迹
        gpsMap.forEach { (deviceId, gps) ->
            val (gcjLat, gcjLng) = CoordinateConverter.wgs84ToGcj02(gps.lat, gps.lng)
            val latLng = LatLng(gcjLat, gcjLng)
            val existing = markers[deviceId]
            if (existing == null) {
                markers[deviceId] = tencentMap.addMarker(
                    MarkerOptions(latLng)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                        .title(deviceNickname(deviceId))
                )
            } else {
                existing.setPosition(latLng)
            }
            renderTrajectory(deviceId)
        }

        // 主控端自身位置（绿色）
        renderSelfLocation()

        // 相机聚焦：优先选中设备，否则最近上报设备；仅位置变化时动画
        val focusId = state.selectedDeviceId
            ?: gpsMap.entries.maxByOrNull { it.value.time }?.key
        focusId?.let { id ->
            val gps = gpsMap[id] ?: return@let
            val pos = gps.lat to gps.lng
            if (id != lastFocusKey || pos != lastFocusPos) {
                lastFocusKey = id
                lastFocusPos = pos
                val (gcjLat, gcjLng) = CoordinateConverter.wgs84ToGcj02(gps.lat, gps.lng)
                tencentMap.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(gcjLat, gcjLng), 15f)
                )
            }
        }

        tvInfo.text = state.latestAlert?.let {
            val from = it.deviceId?.let { id -> deviceNickname(id) }
            val suffix = from?.let { n -> "（$n）" } ?: ""
            "${it.type}${suffix}: ${it.lat}, ${it.lng}（半径 ${it.radius}m）"
        } ?: getString(R.string.waiting_gps)
    }

    private fun renderSelfLocation() {
        val self = state.selfLocation ?: return
        val (lat, lng) = CoordinateConverter.wgs84ToGcj02(self.lat, self.lng)
        val pos = LatLng(lat, lng)
        if (selfMarker == null) {
            selfMarker = tencentMap.addMarker(
                MarkerOptions(pos)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                    .title(getString(R.string.self_label))
            )
        } else {
            selfMarker?.setPosition(pos)
        }
    }

    private fun renderTrajectory(deviceId: String) {
        val traj = state.trajectoryByDevice[deviceId].orEmpty()
        val line = trajectoryPolylines[deviceId]
        if (traj.size < 2) {
            if (line != null) {
                trajectoryPolylines.remove(deviceId)?.remove()
            }
            return
        }
        val points = traj.map {
            val (lat, lng) = CoordinateConverter.wgs84ToGcj02(it.lat, it.lng)
            LatLng(lat, lng)
        }
        if (line == null) {
            trajectoryPolylines[deviceId] = tencentMap.addPolyline(
                PolylineOptions().width(6f).color(TRAJECTORY_COLOR)
            ).also { it.points = points }
        } else {
            line.points = points
        }
    }

    private fun planRoute() {
        val self = state.selfLocation
        if (self == null) {
            Toast.makeText(this, R.string.waiting_gps, Toast.LENGTH_SHORT).show()
            return
        }
        val targetId = state.selectedDeviceId
            ?: state.gpsByDevice.entries.maxByOrNull { it.value.time }?.key
        val target = targetId?.let { state.gpsByDevice[it] }
        if (target == null) {
            Toast.makeText(this, R.string.waiting_gps, Toast.LENGTH_SHORT).show()
            return
        }

        RouteApi.driving(
            key = BuildConfig.TENCENT_MAP_KEY,
            sk = BuildConfig.TENCENT_MAP_SK,
            fromLat = self.lat, fromLng = self.lng,
            toLat = target.lat, toLng = target.lng,
            fromWgs84 = true
        ) { route, error ->
            runOnUiThread {
                if (route == null) {
                    Toast.makeText(this, getString(R.string.route_failed, error ?: ""), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                drawRoute(route.points)
                val km = route.distanceMeters / 1000.0
                val min = route.durationSeconds / 60
                Toast.makeText(
                    this,
                    String.format("路线：%.1f 公里，预计 %d 分钟", km, min),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun drawRoute(points: List<Pair<Double, Double>>) {
        if (points.size < 2) return
        routePolyline?.remove()
        val opts = PolylineOptions().width(10f).color(ROUTE_COLOR)
        points.forEach { (lat, lng) -> opts.add(LatLng(lat, lng)) }
        routePolyline = tencentMap.addPolyline(opts)

        // 相机框住整条路线
        val first = LatLng(points.first().first, points.first().second)
        val last = LatLng(points.last().first, points.last().second)
        tencentMap.animateCamera(
            CameraUpdateFactory.newLatLngBounds(
                com.tencent.tencentmap.mapsdk.maps.model.LatLngBounds.builder()
                    .include(first)
                    .include(last)
                    .build(),
                80
            )
        )
    }

    private fun deviceNickname(deviceId: String): String =
        app.devices().firstOrNull { it.deviceId == deviceId }
            ?.nickname?.takeIf { it.isNotBlank() } ?: deviceId.take(8)

    companion object {
        const val TRAJECTORY_COLOR = 0xFF1565C0.toInt()
        const val ROUTE_COLOR = 0xFF00C853.toInt()
    }
}
