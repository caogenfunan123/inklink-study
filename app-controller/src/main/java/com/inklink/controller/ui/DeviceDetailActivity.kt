package com.inklink.controller.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.inklink.controller.InkControllerApplication
import com.inklink.controller.R
import com.inklink.controller.state.ControllerState

/**
 * 设备详情页（单设备聚合视图）：
 *
 * - 头部：昵称/设备 ID/在线态/最新位置与最后活跃时间
 * - 围栏：该设备已存档围栏列表（名称+半径）
 * - 操作：设为当前设备 / 远程响铃-停止 / 编辑围栏（显式设备穿透，不依赖全局选中态）
 * - 轨迹：跳地图回放并聚焦本设备
 * - 拉取：离线历史轨迹补传（1/3/7 天）+ 进度条 + 取消
 */
class DeviceDetailActivity : AppCompatActivity() {

    private lateinit var app: InkControllerApplication
    private lateinit var state: ControllerState

    private lateinit var deviceId: String
    private lateinit var tvTitle: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var tvOnline: TextView
    private lateinit var tvLatest: TextView
    private lateinit var tvFences: TextView
    private lateinit var btnSelect: Button
    private lateinit var btnRing: Button
    private lateinit var progressPanel: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var btnCancelFetch: Button

    private val listener = object : ControllerState.Listener {
        override fun onStateChanged() = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_detail)
        app = application as InkControllerApplication
        state = app.controllerState

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
            ?: run {
                Toast.makeText(this, R.string.device_not_found, Toast.LENGTH_SHORT).show()
                finish()
                return
            }

        tvTitle = findViewById(R.id.tv_title)
        tvDeviceId = findViewById(R.id.tv_device_id)
        tvOnline = findViewById(R.id.tv_online)
        tvLatest = findViewById(R.id.tv_latest)
        tvFences = findViewById(R.id.tv_fences)
        btnSelect = findViewById(R.id.btn_select)
        btnRing = findViewById(R.id.btn_ring)
        progressPanel = findViewById(R.id.progress_panel)
        progressBar = findViewById(R.id.progress_fetch)
        tvProgress = findViewById(R.id.tv_fetch_progress)
        btnCancelFetch = findViewById(R.id.btn_cancel_fetch)

        val entity = app.deviceRepository.findByDeviceId(deviceId)
        tvTitle.text = entity?.nickname?.takeIf { it.isNotBlank() } ?: deviceId

        btnSelect.setOnClickListener {
            app.selectDevice(deviceId)
            render()
        }
        btnRing.setOnClickListener { toggleRing() }
        findViewById<Button>(R.id.btn_fence).setOnClickListener {
            startActivity(
                Intent(this, FenceEditActivity::class.java)
                    .putExtra(FenceEditActivity.EXTRA_DEVICE_ID, deviceId)
            )
        }
        findViewById<Button>(R.id.btn_track).setOnClickListener {
            startActivity(
                Intent(this, MapActivity::class.java)
                    .putExtra(InkControllerApplication.EXTRA_FOCUS_DEVICE, deviceId)
            )
        }
        findViewById<Button>(R.id.btn_fetch_history).setOnClickListener { showFetchDialog() }
        btnCancelFetch.setOnClickListener {
            if (state.historyProgress?.deviceId == deviceId && state.historyProgress?.done == false && state.historyProgress?.failed == false) {
                app.cancelHistory()
            }
            progressPanel.visibility = android.view.View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        state.addListener(listener)
        render()
    }

    override fun onPause() {
        state.removeListener(listener)
        super.onPause()
    }

    private fun toggleRing() {
        val ringing = state.isRingingByDevice[deviceId] == true
        if (ringing) app.sendStopRing(deviceId) else app.sendRing(deviceId)
    }

    private fun render() {
        val entity = app.deviceRepository.findByDeviceId(deviceId)
        tvTitle.text = entity?.nickname?.takeIf { it.isNotBlank() } ?: deviceId
        tvDeviceId.text = getString(R.string.device_id_label, deviceId)

        val online = state.isOnline(deviceId)
        tvOnline.text = getString(
            R.string.device_detail_status,
            getString(if (online) R.string.online else R.string.offline),
            getString(if (state.selectedDeviceId == deviceId) R.string.is_current_target else R.string.not_current_target)
        )

        val gps = state.gpsByDevice[deviceId]
        val lastSeen = state.lastSeenByDevice[deviceId]
        tvLatest.text = if (gps != null) {
            getString(
                R.string.device_detail_latest,
                gps.lat, gps.lng,
                (System.currentTimeMillis() - (lastSeen ?: gps.time)) / 1000
            )
        } else {
            getString(R.string.waiting_gps)
        }

        val fences = entity?.fences
        tvFences.text = if (fences.isNullOrEmpty()) {
            getString(R.string.no_fence_configured)
        } else {
            getString(R.string.fence_list_count, fences.size) + "\n" +
                fences.mapIndexed { i, f -> "${i + 1}. ${f.name ?: "围栏"} ${f.radius.toInt()}m" }
                    .joinToString("\n")
        }

        btnSelect.isEnabled = state.selectedDeviceId != deviceId
        btnSelect.text = getString(
            if (state.selectedDeviceId == deviceId) R.string.already_current_target else R.string.set_as_current_target
        )
        btnRing.text = getString(
            if (state.isRingingByDevice[deviceId] == true) R.string.stop_ring else R.string.ring
        )

        // 本设备的拉取进度（其余设备的进度不干扰本页）；
        // 完成后保留结果文本至用户点击关闭，避免转瞬即逝看不到合并点数
        val progress = state.historyProgress?.takeIf { it.deviceId == deviceId }
        if (progress != null && !progress.done && !progress.failed) {
            progressPanel.visibility = android.view.View.VISIBLE
            progressBar.visibility = android.view.View.VISIBLE
            btnCancelFetch.setText(R.string.cancel)
            progressBar.max = 100
            progressBar.progress = (progress.received * 100 / progress.total.coerceAtLeast(1)).coerceIn(0, 100)
            tvProgress.text = getString(R.string.fetch_progress_text, progress.received, progress.total, progress.inserted)
        } else if (progress != null) {
            progressPanel.visibility = android.view.View.VISIBLE
            progressBar.visibility = android.view.View.GONE
            btnCancelFetch.setText(R.string.close)
            tvProgress.text = if (progress.done) {
                getString(R.string.fetch_history_done, progress.inserted)
            } else {
                getString(R.string.fetch_history_failed, progress.failedReason ?: "")
            }
        } else {
            progressPanel.visibility = android.view.View.GONE
        }
    }

    private fun showFetchDialog() {
        val options = arrayOf("最近 1 天", "最近 3 天", "最近 7 天")
        val days = intArrayOf(1, 3, 7)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fetch_history_title, tvTitle.text))
            .setItems(options) { _, which ->
                app.requestHistory(deviceId, days[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"
    }
}
