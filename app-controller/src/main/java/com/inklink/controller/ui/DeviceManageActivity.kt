package com.inklink.controller.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.inklink.controller.InkControllerApplication
import com.inklink.controller.R
import com.inklink.controller.data.DeviceEntity
import com.inklink.controller.state.ControllerState

/**
 * 设备管理页面：绑定多台受控端（deviceId + 备注），点选作为当前操作目标。
 *
 * - 点条目：选中该设备作为定向消息/围栏下发的目标。
 * - 长按条目：修改备注 / 删除 / 拉取历史轨迹 / 查看详情（单设备聚合页）。
 */
class DeviceManageActivity : AppCompatActivity() {

    private lateinit var app: InkControllerApplication
    private lateinit var state: ControllerState
    private lateinit var listContainer: LinearLayout
    private lateinit var tvSelected: TextView

    private val listener = object : ControllerState.Listener {
        override fun onStateChanged() {
            render()
            // 历史拉取进度：完成/失败弹提示（用 lastNotifiedReqId 防同一任务重复弹）
            val progress = state.historyProgress ?: return
            if (progress.reqId == lastNotifiedReqId) return
            if (progress.done) {
                lastNotifiedReqId = progress.reqId
                Toast.makeText(
                    this@DeviceManageActivity,
                    getString(R.string.fetch_history_done, progress.inserted),
                    Toast.LENGTH_LONG
                ).show()
            } else if (progress.failed) {
                lastNotifiedReqId = progress.reqId
                Toast.makeText(
                    this@DeviceManageActivity,
                    getString(R.string.fetch_history_failed, progress.failedReason ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private var lastNotifiedReqId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_manage)
        app = application as InkControllerApplication
        state = app.controllerState

        listContainer = findViewById(R.id.device_list_container)
        tvSelected = findViewById(R.id.tv_selected_device)

        findViewById<Button>(R.id.btn_add_device).setOnClickListener { showAddDialog() }
        state.addListener(listener)
        render()
    }

    override fun onDestroy() {
        state.removeListener(listener)
        super.onDestroy()
    }

    private fun render() {
        listContainer.removeAllViews()
        val devices = app.devices()
        if (devices.isEmpty()) {
            listContainer.addView(makeLabel(getString(R.string.no_device)))
        }
        devices.forEach { device ->
            listContainer.addView(makeDeviceRow(device))
        }
        tvSelected.text = getString(
            R.string.selected_device,
            state.selectedDeviceId?.let { id -> deviceNickname(id) } ?: getString(R.string.none)
        )
    }

    private fun deviceNickname(deviceId: String): String =
        app.devices().firstOrNull { it.deviceId == deviceId }?.nickname?.takeIf { it.isNotBlank() }
            ?: deviceId.take(8)

    private fun makeLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setPadding(0, 16, 0, 16)
    }

    private fun makeDeviceRow(device: DeviceEntity): TextView {
        val online = state.isOnline(device.deviceId)
        val selected = state.selectedDeviceId == device.deviceId
        val status = getString(if (online) R.string.online else R.string.offline)
        val nickname = device.nickname.takeIf { it.isNotBlank() } ?: getString(R.string.unnamed)
        val label = TextView(this).apply {
            text = "${if (selected) "✓ " else ""}$nickname（${device.deviceId.take(8)}）· $status"
            textSize = 16f
            setPadding(0, 16, 0, 16)
            setOnClickListener {
                app.selectDevice(device.deviceId)
                Toast.makeText(
                    this@DeviceManageActivity,
                    getString(R.string.device_selected, nickname),
                    Toast.LENGTH_SHORT
                ).show()
                render()
            }
            setOnLongClickListener {
                showDeviceActions(device)
                true
            }
        }
        return label
    }

    private fun showAddDialog() {
        val idEdit = EditText(this).apply { hint = getString(R.string.device_id_hint) }
        val nameEdit = EditText(this).apply { hint = getString(R.string.nickname_hint) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(idEdit)
            addView(nameEdit)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_device)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val deviceId = idEdit.text.toString().trim()
                if (deviceId.isEmpty()) {
                    Toast.makeText(this, R.string.device_id_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                app.addDevice(deviceId, nameEdit.text.toString().trim())
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeviceActions(device: DeviceEntity) {
        val items = arrayOf(
            getString(R.string.edit_nickname),
            getString(R.string.delete),
            getString(R.string.fetch_history),
            getString(R.string.device_detail)
        )
        AlertDialog.Builder(this)
            .setTitle(device.nickname.ifBlank { device.deviceId.take(8) })
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRenameDialog(device)
                    1 -> {
                        app.removeDevice(device.deviceId)
                        render()
                    }
                    2 -> showHistoryFetchDialog(device)
                    3 -> startActivity(
                        Intent(this, DeviceDetailActivity::class.java)
                            .putExtra(DeviceDetailActivity.EXTRA_DEVICE_ID, device.deviceId)
                    )
                }
            }
            .show()
    }

    /** 选择拉取时长并发起补传；进度与结果由 [onStateChanged] 中的 historyProgress 观察提示。 */
    private fun showHistoryFetchDialog(device: DeviceEntity) {
        val options = arrayOf("最近 1 天", "最近 3 天", "最近 7 天")
        val days = intArrayOf(1, 3, 7)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fetch_history_title, device.nickname.ifBlank { device.deviceId.take(8) }))
            .setItems(options) { _, which ->
                app.requestHistory(device.deviceId, days[which])
                Toast.makeText(this, R.string.fetch_history_started, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog(device: DeviceEntity) {
        val nameEdit = EditText(this).apply {
            hint = getString(R.string.nickname_hint)
            setText(device.nickname)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameEdit)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_nickname)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                app.updateDeviceNickname(device.deviceId, nameEdit.text.toString().trim())
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
