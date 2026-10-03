package com.inklink.controller.ui

import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.inklink.common.protocol.InkMessage
import com.inklink.common.protocol.MessageType
import com.inklink.common.service.geofence.GeofenceConfig
import com.inklink.common.utils.CoordinateConverter
import com.inklink.controller.InkControllerApplication
import com.inklink.controller.R
import com.tencent.tencentmap.mapsdk.maps.CameraUpdateFactory
import com.tencent.tencentmap.mapsdk.maps.MapView
import com.tencent.tencentmap.mapsdk.maps.TencentMap
import com.tencent.tencentmap.mapsdk.maps.model.Circle
import com.tencent.tencentmap.mapsdk.maps.model.CircleOptions
import com.tencent.tencentmap.mapsdk.maps.model.LatLng

/**
 * 围栏编辑页面（多围栏）：点击地图选择圆心，SeekBar 调整半径，「添加围栏」入列，
 * 支持命名与删除，「下发全部围栏」把整个列表下发 GEOFENCE_CONFIG 给受控端。
 *
 * 协议向后兼容：列表第 0 个同时写入旧字段 lat/lng/radius（旧受控端只认主围栏）。
 */
class FenceEditActivity : AppCompatActivity(), TencentMap.OnMapClickListener {

    private lateinit var mapView: MapView
    private lateinit var tencentMap: TencentMap
    private lateinit var tvRadius: TextView
    private lateinit var tvFenceList: TextView

    /** 待下发围栏列表（WGS-84），第 0 个为主围栏。 */
    private val pendingFences = mutableListOf<GeofenceConfig.FenceEntry>()

    /** 与 pendingFences 索引对应的地图圆覆盖物。 */
    private val fenceCircles = mutableListOf<Circle>()

    private var center: LatLng? = null
    private var draftCircle: Circle? = null
    private var radius = 300.0
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fence_edit)

        mapView = findViewById(R.id.map_view)
        tvRadius = findViewById(R.id.tv_radius)
        tvFenceList = findViewById(R.id.tv_fence_list)
        tencentMap = mapView.map
        tencentMap.setOnMapClickListener(this)

        // 初始中心：选中设备的最新位置（若已选中），否则最近一次上报位置
        val app = application as InkControllerApplication
        val gps = app.controllerState.selectedDeviceId
            ?.let { app.controllerState.gpsByDevice[it] }
            ?: app.controllerState.latestGps
        gps?.let {
            val (gcjLat, gcjLng) = CoordinateConverter.wgs84ToGcj02(it.lat, it.lng)
            center = LatLng(gcjLat, gcjLng)
            tencentMap.animateCamera(CameraUpdateFactory.newLatLngZoom(center!!, 15f))
            redrawDraftCircle()
        }

        val seekRadius = findViewById<SeekBar>(R.id.seek_radius)
        seekRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                radius = (progress + 100).toDouble()
                updateRadiusLabel()
                redrawDraftCircle()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        updateRadiusLabel()

        // 预加载该设备已存档围栏
        app.controllerState.selectedDeviceId
            ?.let { app.deviceRepository.findByDeviceId(it)?.fences }
            ?.let { saved ->
                pendingFences.addAll(saved)
                pendingFences.forEachIndexed { i, f -> addCircle(i, f) }
                updateFenceListLabel()
            }

        findViewById<Button>(R.id.btn_add_fence).setOnClickListener { addCurrentAsFence() }
        findViewById<Button>(R.id.btn_clear_fences).setOnClickListener { clearFences() }
        findViewById<Button>(R.id.btn_submit).setOnClickListener { submit() }
        tvFenceList.setOnClickListener { showRemoveDialog() }
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
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onMapClick(latLng: LatLng) {
        center = latLng
        redrawDraftCircle()
    }

    private fun updateRadiusLabel() {
        tvRadius.text = getString(R.string.radius_label, radius.toInt())
    }

    /** 当前点选中的候选圆（黄色虚线感观：浅红细边）。 */
    private fun redrawDraftCircle() {
        draftCircle?.remove()
        draftCircle = null
        center?.let { c ->
            draftCircle = tencentMap.addCircle(
                CircleOptions()
                    .center(c)
                    .radius(radius)
                    .strokeColor(Color.YELLOW)
                    .strokeWidth(3f)
                    .fillColor(Color.argb(30, 255, 200, 0))
            )
        }
    }

    private fun addCurrentAsFence() {
        val c = center
        if (c == null) {
            Toast.makeText(this, R.string.pick_center, Toast.LENGTH_SHORT).show()
            return
        }
        val defaultName = getString(R.string.add_fence) + (pendingFences.size + 1)
        val input = LayoutInflater.from(this).inflate(
            R.layout.dialog_fence_name, findViewById(android.R.id.content), false
        ) as android.widget.LinearLayout
        val editText = input.findViewById<EditText>(R.id.et_fence_name)
        editText.hint = defaultName
        AlertDialog.Builder(this)
            .setTitle(R.string.add_fence)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = editText.text.toString().trim()
                val (wgsLat, wgsLng) = CoordinateConverter.gcj02ToWgs84(c.latitude, c.longitude)
                val entry = GeofenceConfig.FenceEntry(
                    lat = wgsLat,
                    lng = wgsLng,
                    radius = radius,
                    name = if (TextUtils.isEmpty(name)) defaultName else name
                )
                addCircle(pendingFences.size, entry)
                pendingFences.add(entry)
                updateFenceListLabel()
                Toast.makeText(this, R.string.fence_added, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addCircle(index: Int, entry: GeofenceConfig.FenceEntry) {
        val (gcjLat, gcjLng) = CoordinateConverter.wgs84ToGcj02(entry.lat, entry.lng)
        val circle = tencentMap.addCircle(
            CircleOptions()
                .center(LatLng(gcjLat, gcjLng))
                .radius(entry.radius)
                .strokeColor(if (index == 0) Color.RED else Color.rgb(255, 96, 96))
                .strokeWidth(2f)
                .fillColor(Color.argb(50, 255, 0, 0))
        )
        fenceCircles.add(index, circle)
    }

    private fun rebuildFenceCircles() {
        fenceCircles.forEach { it.remove() }
        fenceCircles.clear()
        pendingFences.forEachIndexed { i, f -> addCircle(i, f) }
    }

    private fun clearFences() {
        if (pendingFences.isEmpty()) return
        pendingFences.clear()
        rebuildFenceCircles()
        updateFenceListLabel()
        Toast.makeText(this, R.string.fence_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun showRemoveDialog() {
        if (pendingFences.isEmpty()) return
        val labels = pendingFences.mapIndexed { i, f ->
            val name = f.name ?: "围栏${i + 1}"
            "$name (${f.radius.toInt()}m)"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_fence)
            .setItems(labels) { _, which ->
                pendingFences.removeAt(which)
                rebuildFenceCircles()
                updateFenceListLabel()
                Toast.makeText(this, R.string.fence_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateFenceListLabel() {
        tvFenceList.text = if (pendingFences.isEmpty()) {
            getString(R.string.fence_list_empty)
        } else {
            getString(R.string.fence_list_label) + "\n" + pendingFences.mapIndexed { i, f ->
                val name = f.name ?: "围栏${i + 1}"
                "$name ${f.radius.toInt()}m"
            }.joinToString("\n")
        }
    }

    private fun submit() {
        if (pendingFences.isEmpty()) {
            Toast.makeText(this, R.string.pick_center, Toast.LENGTH_SHORT).show()
            return
        }
        val app = application as InkControllerApplication
        val target = app.controllerState.selectedDeviceId
        if (target == null) {
            Toast.makeText(this, R.string.no_target_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val cfg = GeofenceConfig.fromList(pendingFences)
        app.transportManager.sendMessage(
            InkMessage.text(MessageType.GEOFENCE_CONFIG, gson.toJson(cfg), from = app.deviceId, target = target)
        )
        // 主控端记住最后下发的围栏（WGS-84），地图页可视化
        app.deviceRepository.updateFences(target, pendingFences.toList())
        Toast.makeText(this, R.string.fence_sent, Toast.LENGTH_SHORT).show()
        finish()
    }
}
