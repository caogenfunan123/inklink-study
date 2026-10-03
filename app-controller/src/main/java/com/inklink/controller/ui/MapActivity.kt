package com.inklink.controller.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.inklink.common.service.geofence.GeofenceConfig
import com.inklink.common.utils.CoordinateConverter
import com.inklink.controller.BuildConfig
import com.inklink.controller.InkControllerApplication
import com.inklink.controller.R
import com.inklink.controller.data.TrackStore
import com.inklink.controller.service.RouteApi
import com.inklink.controller.state.ControllerState
import com.tencent.tencentmap.mapsdk.maps.CameraUpdateFactory
import com.tencent.tencentmap.mapsdk.maps.MapView
import com.tencent.tencentmap.mapsdk.maps.TencentMap
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory
import com.tencent.tencentmap.mapsdk.maps.model.Circle
import com.tencent.tencentmap.mapsdk.maps.model.CircleOptions
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import com.tencent.tencentmap.mapsdk.maps.model.LatLngBounds
import com.tencent.tencentmap.mapsdk.maps.model.Marker
import com.tencent.tencentmap.mapsdk.maps.model.MarkerOptions
import com.tencent.tencentmap.mapsdk.maps.model.Polyline
import com.tencent.tencentmap.mapsdk.maps.model.PolylineOptions

/**
 * 主控端地图页面：
 * - 主控端自身位置显示为绿色 Marker，受控端位置统一显示为红色 Marker。
 * - 每个受控端绘制历史轨迹折线，并显示最后下发的电子围栏圈。
 * - 历史轨迹回放：按设备按天从本地轨迹文件回放（起点绿旗/终点橙旗 + 里程统计）。
 * - GPX 导出：将某天轨迹导出为 GPX 1.1 文件并通过系统分享。
 * - 支持主动拉取受控端位置、从自身位置到受控端规划驾车路线。
 * - 支持从告警通知点击直达并聚焦指定设备（EXTRA_FOCUS_DEVICE）。
 */
class MapActivity : AppCompatActivity() {

    private lateinit var app: InkControllerApplication
    private lateinit var mapView: MapView
    private lateinit var tencentMap: TencentMap
    private lateinit var state: ControllerState
    private lateinit var tvInfo: TextView

    private val markers = mutableMapOf<String, Marker>()
    private val trajectoryPolylines = mutableMapOf<String, Polyline>()
    private val fenceCircles = mutableMapOf<String, MutableList<Circle>>()
    private var selfMarker: Marker? = null
    private var routePolyline: Polyline? = null

    // 历史回放图层（与实时轨迹、路线互不干扰）
    private var playbackPolyline: Polyline? = null
    private var playbackStartMarker: Marker? = null
    private var playbackEndMarker: Marker? = null

    /** 回放统计文案；回放期间 render() 优先展示，避免被告警/等待文案覆盖。 */
    private var playbackStats: String? = null

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
        findViewById<Button>(R.id.btn_history).setOnClickListener { showHistoryDialog() }
        findViewById<Button>(R.id.btn_export).setOnClickListener { showExportDialog() }

        app.startSelfLocation()
        handleIntent(intent)
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
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

    /** 告警通知点击跳转：聚焦指定设备（选中即触发相机动画）。 */
    private fun handleIntent(intent: Intent?) {
        val focus = intent?.getStringExtra(InkControllerApplication.EXTRA_FOCUS_DEVICE) ?: return
        app.selectDevice(focus)
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

        // 已下发围栏可视化（与实时 GPS 无关，配置过就画）
        renderFences()

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

        tvInfo.text = playbackStats ?: state.latestAlert?.let {
            val from = it.deviceId?.let { id -> deviceNickname(id) }
            val suffix = from?.let { n -> "（$n）" } ?: ""
            "${it.type}${suffix}: ${it.lat}, ${it.lng}（半径 ${it.radius}m）"
        } ?: getString(R.string.waiting_gps)
    }

    /** 画出每台已配置围栏设备的围栏圈（多围栏优先，本地记录的 WGS-84 转 GCJ-02 显示）。 */
    private fun renderFences() {
        val devices = app.devices().associateBy { it.deviceId }
        fenceCircles.keys.filter { id ->
            val d = devices[id]
            d == null || (d.fences.isNullOrEmpty() &&
                (d.fenceLat == null || d.fenceLng == null || d.fenceRadiusM == null))
        }.forEach { id ->
            fenceCircles.remove(id)?.forEach { it.remove() }
        }

        devices.values.forEach { d ->
            val entries = d.fences?.takeIf { it.isNotEmpty() } ?: listOfNotNull(
                d.fenceLat?.let { lat ->
                    d.fenceLng?.let { lng ->
                        d.fenceRadiusM?.let { r -> GeofenceConfig.FenceEntry(lat, lng, r) }
                    }
                }
            )
            if (entries.isEmpty()) return@forEach

            val existing = fenceCircles[d.deviceId]
            if (existing != null && existing.size == entries.size) {
                entries.forEachIndexed { i, e ->
                    val (gcjLat, gcjLng) = CoordinateConverter.wgs84ToGcj02(e.lat, e.lng)
                    existing[i].center = LatLng(gcjLat, gcjLng)
                    existing[i].radius = e.radius
                }
            } else {
                fenceCircles.remove(d.deviceId)?.forEach { it.remove() }
                fenceCircles[d.deviceId] = entries.map { e ->
                    val (gcjLat, gcjLng) = CoordinateConverter.wgs84ToGcj02(e.lat, e.lng)
                    tencentMap.addCircle(
                        CircleOptions()
                            .center(LatLng(gcjLat, gcjLng))
                            .radius(e.radius)
                            .strokeColor(FENCE_COLOR)
                            .strokeWidth(2f)
                            .fillColor(FENCE_FILL)
                    )
                }.toMutableList()
            }
        }
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

    private fun targetDeviceId(): String? =
        state.selectedDeviceId ?: state.gpsByDevice.keys.firstOrNull()

    /** 历史回放：选择设备有数据的日期，从本地轨迹文件回放。 */
    private fun showHistoryDialog() {
        val target = targetDeviceId()
        if (target == null) {
            Toast.makeText(this, R.string.waiting_gps, Toast.LENGTH_SHORT).show()
            return
        }
        val days = app.trackStore.listDays(target)
        if (days.isEmpty()) {
            Toast.makeText(this, R.string.no_track_data, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("${deviceNickname(target)} · ${getString(R.string.history_track)}")
            .setItems(days.toTypedArray()) { _, which -> playDay(target, days[which]) }
        if (playbackStats != null) {
            dialog.setNegativeButton(R.string.exit_playback) { _, _ -> exitPlayback() }
        }
        dialog.show()
    }

    private fun playDay(deviceId: String, day: String) {
        Toast.makeText(this, getString(R.string.playback_loading, day), Toast.LENGTH_SHORT).show()
        Thread {
            val points = app.trackStore.simplify(app.trackStore.readDay(deviceId, day))
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (points.size < 2) {
                    Toast.makeText(this, R.string.no_track_data, Toast.LENGTH_SHORT).show()
                } else {
                    drawPlayback(deviceId, day, points)
                }
            }
        }.start()
    }

    private fun drawPlayback(deviceId: String, day: String, points: List<TrackStore.TrackPoint>) {
        exitPlaybackOverlays()
        val opts = PolylineOptions().width(10f).color(PLAYBACK_COLOR)
        var meters = 0.0
        var prev: TrackStore.TrackPoint? = null
        val gcjPoints = points.map { p ->
            prev?.let { meters += app.trackStore.haversineMeters(it.la, it.lo, p.la, p.lo) }
            prev = p
            val (lat, lng) = CoordinateConverter.wgs84ToGcj02(p.la, p.lo)
            LatLng(lat, lng)
        }
        gcjPoints.forEach { opts.add(it) }
        playbackPolyline = tencentMap.addPolyline(opts)

        playbackStartMarker = tencentMap.addMarker(
            MarkerOptions(gcjPoints.first())
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                .title(getString(R.string.playback_start))
        )
        playbackEndMarker = tencentMap.addMarker(
            MarkerOptions(gcjPoints.last())
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                .title(getString(R.string.playback_end))
        )

        val builder = LatLngBounds.builder()
        gcjPoints.forEach { builder.include(it) }
        tencentMap.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 100))

        val km = meters / 1000.0
        val minutes = (points.last().t - points.first().t) / 60000
        playbackStats = getString(R.string.track_stats, day, km, points.size, minutes)
        Toast.makeText(this, R.string.playback_started, Toast.LENGTH_SHORT).show()
        render()
    }

    private fun exitPlayback() {
        exitPlaybackOverlays()
        playbackStats = null
        render()
    }

    private fun exitPlaybackOverlays() {
        playbackPolyline?.remove()
        playbackPolyline = null
        playbackStartMarker?.remove()
        playbackStartMarker = null
        playbackEndMarker?.remove()
        playbackEndMarker = null
    }

    /** GPX 导出：选日期 → 后台导出 → 系统分享。 */
    private fun showExportDialog() {
        val target = targetDeviceId()
        if (target == null) {
            Toast.makeText(this, R.string.waiting_gps, Toast.LENGTH_SHORT).show()
            return
        }
        val days = app.trackStore.listDays(target)
        if (days.isEmpty()) {
            Toast.makeText(this, R.string.no_track_data, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("${deviceNickname(target)} · ${getString(R.string.export_gpx)}")
            .setItems(days.toTypedArray()) { _, which -> exportGpx(target, days[which]) }
            .show()
    }

    private fun exportGpx(deviceId: String, day: String) {
        Thread {
            val file = app.trackStore.exportGpx(deviceId, day)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (file == null) {
                    Toast.makeText(this, R.string.no_track_data, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/gpx+xml"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, getString(R.string.export_gpx)))
            }
        }.start()
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
                if (isDestroyed || isFinishing) return@runOnUiThread
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
                LatLngBounds.builder()
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
        const val PLAYBACK_COLOR = 0xFFFF6D00.toInt()
        const val FENCE_COLOR = 0xFFE53935.toInt()
        const val FENCE_FILL = 0x28E53935
    }
}
