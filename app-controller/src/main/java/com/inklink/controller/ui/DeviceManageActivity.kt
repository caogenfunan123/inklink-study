package com.inklink.controller.ui

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
 * - 长按条目：删除或修改备注。
 */
class DeviceManageActivity : AppCompatActivity() {

    private lateinit var app: InkControllerApplication
    private lateinit var state: ControllerState
    private lateinit var listContainer: LinearLayout
    private lateinit var tvSelected: TextView

    private val listener = object : ControllerState.Listener {
        override fun onStateChanged() = render()
    }

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
            getString(R.string.delete)
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
                }
            }
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
