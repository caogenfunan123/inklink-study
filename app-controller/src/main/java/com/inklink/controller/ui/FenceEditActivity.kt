package com.inklink.controller.ui

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
 * 围栏编辑页面：点击地图选择圆心，SeekBar 调整半径，下发 GEOFENCE_CONFIG 给受控端。
 */
class FenceEditActivity : AppCompatActivity(), TencentMap.OnMapClickListener {

    private lateinit var mapView: MapView
    private lateinit var tencentMap: TencentMap
    private lateinit var tvRadius: TextView

    private var center: LatLng? = null
    private var circle: Circle? = null
    private var radius = 300.0
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fence_edit)

        mapView = findViewById(R.id.map_view)
        tvRadius = findViewById(R.id.tv_radius)
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
            redrawCircle()
        }

        val seekRadius = findViewById<SeekBar>(R.id.seek_radius)
        seekRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                radius = (progress + 100).toDouble()
                updateRadiusLabel()
                redrawCircle()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        updateRadiusLabel()

        findViewById<Button>(R.id.btn_submit).setOnClickListener { submit() }
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
        redrawCircle()
    }

    private fun updateRadiusLabel() {
        tvRadius.text = getString(R.string.radius_label, radius.toInt())
    }

    private fun redrawCircle() {
        circle?.remove()
        circle = null
        center?.let { c ->
            circle = tencentMap.addCircle(
                CircleOptions()
                    .center(c)
                    .radius(radius)
                    .strokeColor(Color.RED)
                    .strokeWidth(2f)
                    .fillColor(Color.argb(50, 255, 0, 0))
            )
        }
    }

    private fun submit() {
        val c = center
        if (c == null) {
            Toast.makeText(this, R.string.pick_center, Toast.LENGTH_SHORT).show()
            return
        }
        val app = application as InkControllerApplication
        val target = app.controllerState.selectedDeviceId
        if (target == null) {
            Toast.makeText(this, R.string.no_target_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val (wgsLat, wgsLng) = CoordinateConverter.gcj02ToWgs84(c.latitude, c.longitude)
        val cfg = GeofenceConfig(wgsLat, wgsLng, radius)
        app.transportManager.sendMessage(
            InkMessage.text(MessageType.GEOFENCE_CONFIG, gson.toJson(cfg), from = app.deviceId, target = target)
        )
        // 主控端记住最后下发的围栏（WGS-84），地图页可视化
        app.deviceRepository.updateFence(target, wgsLat, wgsLng, radius)
        Toast.makeText(this, R.string.fence_sent, Toast.LENGTH_SHORT).show()
        finish()
    }
}
