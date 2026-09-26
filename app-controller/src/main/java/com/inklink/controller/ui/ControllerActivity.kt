package com.inklink.controller.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.inklink.common.protocol.InkMessage
import com.inklink.common.protocol.MessageType
import com.inklink.common.protocol.payload.AckPayload
import com.inklink.common.service.discovery.DiscoveredDevice
import com.inklink.common.service.discovery.UdpDiscovery
import com.inklink.common.transport.LocalWsTransport
import com.inklink.common.transport.TransportMode
import com.inklink.common.utils.ImageUtil
import com.inklink.common.utils.PermissionUtil
import com.inklink.controller.InkControllerApplication
import com.inklink.controller.R
import com.inklink.controller.data.DeviceEntity
import com.inklink.controller.state.ControllerState
import com.inklink.controller.state.DeviceOnlineState
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主控端现代科技风 Dashboard：
 * - 受控设备卡片流监控（三态徽章、电量、定位、响铃状态）。
 * - 强控指令一键直达（响铃/停铃、残影深度刷、定位刷新、语音对讲、消息聊天）。
 * - ACK 自动监听并显示执行回执。
 */
class ControllerActivity : AppCompatActivity() {

    private lateinit var app: InkControllerApplication
    private lateinit var state: ControllerState

    private lateinit var tvStatus: TextView
    private lateinit var cardsContainer: LinearLayout
    private lateinit var etText: EditText
    private lateinit var btnCall: Button

    private val ackListener = { ack: AckPayload ->
        val desc = when (ack.code) {
            AckPayload.CODE_OK -> "指令执行成功"
            AckPayload.CODE_VOLUME_RESTRICTED -> "受控端系统音量限制，已尽最大音量响铃"
            AckPayload.CODE_IDEMPOTENT_DROP -> "受控端已收到相同指令，幂等忽略"
            AckPayload.CODE_PERMISSION_DENIED -> "受控端权限不足: ${ack.errorMsg}"
            else -> "受控端返回错误 [${ack.code}]: ${ack.errorMsg}"
        }
        Toast.makeText(this, desc, Toast.LENGTH_SHORT).show()
    }

    private val listener = object : ControllerState.Listener {
        override fun onStateChanged() = render()
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result?.contents
        if (contents.isNullOrBlank()) {
            Toast.makeText(this, R.string.scan_qr_invalid, Toast.LENGTH_SHORT).show()
        } else {
            parseAndConnect(contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controller)
        app = application as InkControllerApplication
        state = app.controllerState

        bindViews()
        requestPermissions()
        state.addListener(listener)
        state.addAckListener(ackListener)
        render()
    }

    override fun onDestroy() {
        state.removeListener(listener)
        state.removeAckListener(ackListener)
        super.onDestroy()
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tv_status)
        cardsContainer = findViewById(R.id.cards_container)
        etText = findViewById(R.id.et_text)
        btnCall = findViewById(R.id.btn_call)

        findViewById<Button>(R.id.btn_settings).setOnClickListener { showTransportSettings() }
        findViewById<Button>(R.id.btn_device_manage).setOnClickListener { openDeviceManage() }
        findViewById<Button>(R.id.btn_send_text).setOnClickListener { sendText() }
        findViewById<Button>(R.id.btn_send_image).setOnClickListener { pickImage() }
        findViewById<Button>(R.id.btn_clear).setOnClickListener { sendClear() }
        findViewById<Button>(R.id.btn_call).setOnClickListener { toggleCall() }
        findViewById<Button>(R.id.btn_map).setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
        findViewById<Button>(R.id.btn_fence).setOnClickListener {
            startActivity(Intent(this, FenceEditActivity::class.java))
        }
    }

    private fun openDeviceManage() {
        startActivity(Intent(this, DeviceManageActivity::class.java))
    }

    private fun requestPermissions() {
        val perms = mutableListOf(PermissionUtil.RECORD_AUDIO, PermissionUtil.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (PermissionUtil.needsRequest(this, *perms.toTypedArray())) {
            PermissionUtil.request(this, REQUEST_PERMISSIONS, *perms.toTypedArray())
        }
    }

    private fun parseAndConnect(raw: String) {
        val content = raw.trim()
        val hostPort = when {
            content.startsWith("ws://") -> content.removePrefix("ws://")
            content.startsWith("wss://") -> content.removePrefix("wss://")
            content.startsWith("http://") -> content.removePrefix("http://")
            content.startsWith("https://") -> content.removePrefix("https://")
            else -> content
        }
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', LocalWsTransport.DEFAULT_PORT.toString())
            .toIntOrNull() ?: LocalWsTransport.DEFAULT_PORT
        if (host.isEmpty()) {
            Toast.makeText(this, R.string.scan_qr_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        app.connectLocal(host, port)
        render()
    }

    private fun showTransportSettings() {
        val items = arrayOf(
            getString(R.string.mode_local),
            getString(R.string.mode_relay),
            getString(R.string.mode_ably)
        )
        var selected = when (app.transportManager.currentMode()) {
            TransportMode.RELAY -> 1
            TransportMode.ABLY -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.transport_settings)
            .setSingleChoiceItems(items, selected) { _, which -> selected = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                when (selected) {
                    0 -> {
                        Toast.makeText(this, "已切为局域网模式", Toast.LENGTH_SHORT).show()
                    }
                    1 -> showRelayConfig()
                    2 -> showAblyConfig()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 运行时填写 Ably Key(公开源码构建内置为空,必须在此填写);留空=回落内置值。 */
    private fun showAblyConfig() {
        val keyEdit = EditText(this).apply { hint = "Ably Root Key(留空=使用内置)" }
        AlertDialog.Builder(this)
            .setTitle("Ably 密钥")
            .setView(keyEdit)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                app.ablyKey = keyEdit.text.toString().trim()
                app.connectAbly()
                render()
                Toast.makeText(this, "已连接 Ably 4G 消息频道", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRelayConfig() {
        val serverEdit = EditText(this).apply { hint = "wss://server:8443" }
        val targetEdit = EditText(this).apply { hint = "受控端设备 ID" }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(serverEdit)
            addView(targetEdit)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.relay_config)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = serverEdit.text.toString().trim()
                val target = targetEdit.text.toString().trim()
                if (url.isNotEmpty() && target.isNotEmpty()) {
                    app.switchRelay(url, target)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sendText() {
        val text = etText.text.toString().trim()
        if (text.isEmpty()) return
        app.transportManager.sendMessage(
            InkMessage.text(MessageType.TEXT, text, from = app.deviceId, target = state.selectedDeviceId)
        )
        etText.text.clear()
        Toast.makeText(this, "投屏文本已发送", Toast.LENGTH_SHORT).show()
    }

    private fun sendClear() {
        app.transportManager.sendMessage(
            InkMessage.control(MessageType.CMD_CLEAR, from = app.deviceId, target = state.selectedDeviceId)
        )
        Toast.makeText(this, "清屏指令已发送", Toast.LENGTH_SHORT).show()
    }

    private fun toggleCall() {
        if (state.inCall) app.stopCall() else app.startCall()
        render()
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_IMAGE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            ImageUtil.compressToBase64(this, uri)?.let { base64 ->
                app.transportManager.sendMessage(
                    InkMessage.text(MessageType.IMAGE, base64, from = app.deviceId, target = state.selectedDeviceId)
                )
                Toast.makeText(this, "投屏图片已发送", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun render() {
        val connected = state.connected
        val statusText = if (connected) "已连接 (Ably 4G 在线)" else "未连接"
        val targetNickname = state.selectedDeviceId?.let { id ->
            app.devices().firstOrNull { it.deviceId == id }?.nickname?.takeIf { it.isNotBlank() } ?: id.take(8)
        }
        tvStatus.text = if (targetNickname != null) "$statusText · 焦点: $targetNickname" else statusText
        btnCall.text = getString(if (state.inCall) R.string.stop_call else R.string.start_call)

        renderDeviceCards()
    }

    private fun renderDeviceCards() {
        cardsContainer.removeAllViews()
        val devices = app.devices().ifEmpty {
            state.gpsByDevice.keys.map { DeviceEntity(it, "") }
        }

        if (devices.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "暂无受控设备，请在「设备管理」中添加或等待设备上报"
                setTextColor(Color.parseColor("#777777"))
                textSize = 13f
                setPadding(0, 16, 0, 16)
            }
            cardsContainer.addView(emptyTv)
            return
        }

        val inflater = LayoutInflater.from(this)
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        devices.forEach { dev ->
            val devId = dev.deviceId
            val card = inflater.inflate(R.layout.item_device_card, cardsContainer, false)
            val tvName = card.findViewById<TextView>(R.id.tv_device_name)
            val tvBadge = card.findViewById<TextView>(R.id.tv_device_state_badge)
            val tvMetrics = card.findViewById<TextView>(R.id.tv_device_metrics)
            val btnRing = card.findViewById<Button>(R.id.btn_card_ring)
            val btnRefresh = card.findViewById<Button>(R.id.btn_card_refresh)
            val btnGps = card.findViewById<Button>(R.id.btn_card_gps)
            val btnChat = card.findViewById<Button>(R.id.btn_card_chat)
            val btnAlert = card.findViewById<Button>(R.id.btn_card_alert)

            val name = dev.nickname.ifBlank { "受控端 (${devId.take(8)})" }
            tvName.text = name

            val onlineState = state.getDeviceOnlineState(devId)
            when (onlineState) {
                DeviceOnlineState.ONLINE_NORMAL -> {
                    tvBadge.text = getString(R.string.online_normal)
                    tvBadge.setTextColor(Color.parseColor("#00E676"))
                    tvBadge.setBackgroundColor(Color.parseColor("#2200E676"))
                }
                DeviceOnlineState.ONLINE_RESTRICTED -> {
                    tvBadge.text = getString(R.string.online_restricted)
                    tvBadge.setTextColor(Color.parseColor("#FFD600"))
                    tvBadge.setBackgroundColor(Color.parseColor("#22FFD600"))
                }
                DeviceOnlineState.OFFLINE -> {
                    tvBadge.text = getString(R.string.offline)
                    tvBadge.setTextColor(Color.parseColor("#FF5252"))
                    tvBadge.setBackgroundColor(Color.parseColor("#22FF5252"))
                }
            }

            val gps = state.gpsByDevice[devId]
            val status = state.statusByDevice[devId]
            val isRinging = state.isRingingByDevice[devId] ?: false
            val lastSeen = state.lastSeenByDevice[devId]

            val batteryInfo = status?.let { "电量: ${it.batteryPct}% ${if (it.isCharging) "(充电中)" else ""}" } ?: "电量: 未知"
            val gpsInfo = gps?.let {
                val sat = if (it.satelliteCount > 0) " (卫星: ${it.satelliteCount})" else ""
                String.format(Locale.US, "坐标: %.5f, %.5f%s 精度: %.1fm", it.lat, it.lng, sat, it.accuracy)
            } ?: "位置: 尚未收到上报"
            val lastSeenStr = lastSeen?.let { "最后通信: ${timeFmt.format(Date(it))}" } ?: "最后通信: --"
            val ringState = if (isRinging) "🚨 【正在响铃报警中】" else "响铃: 正常"

            tvMetrics.text = "$batteryInfo\n$gpsInfo\n$ringState · $lastSeenStr"

            // 学习简报(LEARN_PROGRESS/47):收到过才显示
            card.findViewById<TextView>(R.id.tv_learn_summary).let { tv ->
                state.learnSummaryByDevice[devId]?.let { summary ->
                    tv.text = summary
                    tv.visibility = View.VISIBLE
                }
            }

            if (isRinging) {
                btnRing.text = getString(R.string.btn_siren_stop)
                btnRing.setBackgroundColor(Color.parseColor("#B0BEC5"))
            } else {
                btnRing.text = getString(R.string.btn_siren_ring)
                btnRing.setBackgroundColor(Color.parseColor("#E53935"))
            }

            btnRing.setOnClickListener {
                if (isRinging) {
                    app.sendStopRing(devId)
                    Toast.makeText(this, getString(R.string.siren_stopped), Toast.LENGTH_SHORT).show()
                } else {
                    app.sendRing(devId, durationSec = 15, volumePct = 100)
                    Toast.makeText(this, getString(R.string.siren_started), Toast.LENGTH_SHORT).show()
                }
            }

            btnRefresh.setOnClickListener {
                app.sendDeepRefresh(devId)
                Toast.makeText(this, "已向该设备下发残影深度刷新", Toast.LENGTH_SHORT).show()
            }

            val btnQuickFeed = card.findViewById<Button>(R.id.btn_pet_quick_feed)
            btnQuickFeed?.setOnClickListener {
                val intent = Intent(this, PetDetailActivity::class.java).apply {
                    putExtra("target_device_id", devId)
                }
                startActivity(intent)
            }

            btnGps.setOnClickListener {
                app.requestGps(devId)
                Toast.makeText(this, "已向该设备请求单次高精定位", Toast.LENGTH_SHORT).show()
            }

            btnChat.setOnClickListener {
                app.selectDevice(devId)
                startActivity(Intent(this, ChatActivity::class.java))
            }

            btnAlert.setOnClickListener {
                app.selectDevice(devId)
                showPushAlertDialog(devId, name)
            }

            card.setOnClickListener {
                app.selectDevice(devId)
                Toast.makeText(this, "已聚焦目标: $name", Toast.LENGTH_SHORT).show()
            }

            cardsContainer.addView(card)
        }
    }

    private fun showPushAlertDialog(devId: String, nickname: String) {
        val titleEdit = EditText(this).apply { hint = getString(R.string.push_alert_hint_title) }
        val contentEdit = EditText(this).apply { hint = getString(R.string.push_alert_hint_content) }
        val ringCb = CheckBox(this).apply { text = getString(R.string.push_alert_ring) }
        val notifyCb = CheckBox(this).apply {
            text = getString(R.string.push_alert_notify)
            isChecked = true
        }
        val durationEdit = EditText(this).apply {
            hint = getString(R.string.alert_ring_secs)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("15")
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleEdit)
            addView(contentEdit)
            addView(durationEdit)
            addView(ringCb)
            addView(notifyCb)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.push_alert_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = titleEdit.text.toString().trim()
                val content = contentEdit.text.toString().trim()
                if (title.isEmpty() || content.isEmpty()) {
                    Toast.makeText(this, R.string.push_alert_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val secs = durationEdit.text.toString().toIntOrNull()?.coerceIn(3, 60) ?: 15
                app.sendPushAlert(
                    targetDeviceId = devId,
                    title = title,
                    content = content,
                    ring = ringCb.isChecked,
                    durationSec = secs,
                    needNotification = notifyCb.isChecked
                )
                Toast.makeText(this, getString(R.string.push_alert_sent, nickname), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private const val REQUEST_IMAGE = 101
    }
}
