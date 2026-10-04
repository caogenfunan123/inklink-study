package com.inklink.host.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.inklink.common.transport.LocalWsTransport
import com.inklink.common.transport.TransportMode
import com.inklink.common.utils.DensityUtil
import com.inklink.common.utils.PermissionUtil
import com.inklink.host.InkHostApplication
import com.inklink.host.R
import com.inklink.host.service.InkForegroundService
import com.inklink.host.state.HostScreenState

/**
 * 受控端主界面。
 *
 * - 支持普通手机、手表（圆形/方形屏幕）自适应安全边距。
 * - 待机页：二维码、IP 端口、连接状态。
 * - 运行页：全屏投屏画布（文本+图片）及紧急响铃告警浮层。
 * - 侧边抽屉：电池优化白名单引导、深度刷新、运行日志、网络模式配置。
 */
class HostActivity : AppCompatActivity() {

    private lateinit var hostState: HostScreenState
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var screenScroll: View
    private lateinit var tvScreenText: TextView
    private lateinit var ivScreenImage: ImageView
    private lateinit var idlePanel: View
    private lateinit var ivQr: ImageView
    private lateinit var tvIp: TextView
    private lateinit var tvStatus: TextView
    private lateinit var bannerRinging: View
    private lateinit var btnDismissRing: Button
    private lateinit var bannerAlert: View
    private lateinit var tvAlertTitle: TextView
    private lateinit var tvAlertContent: TextView
    private lateinit var btnDismissAlert: Button

    private var lastQrContent: String? = null

    private val listener = object : HostScreenState.Listener {
        override fun onStateChanged() = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host)
        hostState = (application as InkHostApplication).hostState

        bindViews()
        setupFullscreen()
        applyRoundScreenInset()
        setupDrawer()
        hostState.addListener(listener)
        startIfPermitted()
        render()
    }

    private fun startIfPermitted() {
        val perms = listOf(
            PermissionUtil.LOCATION,
            PermissionUtil.LOCATION_COARSE,
            PermissionUtil.RECORD_AUDIO
        )
        if (PermissionUtil.hasPermissions(this, *perms.toTypedArray())) {
            ensureServiceRunning()
        } else {
            requestPermissions()
        }
    }

    override fun onDestroy() {
        hostState.removeListener(listener)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            ensureServiceRunning()
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            PermissionUtil.LOCATION,
            PermissionUtil.LOCATION_COARSE,
            PermissionUtil.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(PermissionUtil.WRITE_STORAGE)
        }
        if (PermissionUtil.needsRequest(this, *perms.toTypedArray())) {
            PermissionUtil.request(this, REQUEST_PERMISSIONS, *perms.toTypedArray())
        }
    }

    private fun bindViews() {
        drawerLayout = findViewById(R.id.drawer_layout)
        screenScroll = findViewById(R.id.screen_scroll)
        tvScreenText = findViewById(R.id.tv_screen_text)
        ivScreenImage = findViewById(R.id.iv_screen_image)
        idlePanel = findViewById(R.id.idle_panel)
        ivQr = findViewById(R.id.iv_qr)
        tvIp = findViewById(R.id.tv_ip)
        tvStatus = findViewById(R.id.tv_status)
        bannerRinging = findViewById(R.id.banner_ringing)
        btnDismissRing = findViewById(R.id.btn_dismiss_ring)
        bannerAlert = findViewById(R.id.banner_alert)
        tvAlertTitle = findViewById(R.id.tv_alert_title)
        tvAlertContent = findViewById(R.id.tv_alert_content)
        btnDismissAlert = findViewById(R.id.btn_dismiss_alert)

        findViewById<View>(R.id.btn_menu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }

        btnDismissRing.setOnClickListener {
            // 必须同时通知前台服务停掉铃声；只清 UI 标志会留下关不掉的 SirenManager
            runCatching {
                startService(
                    Intent(this, InkForegroundService::class.java)
                        .setAction(InkForegroundService.ACTION_STOP_RING)
                )
            }
            hostState.setRinging(false)
        }

        btnDismissAlert.setOnClickListener {
            hostState.dismissAlert()
        }
    }

    private fun setupDrawer() {
        findViewById<View>(R.id.btn_chat).setOnClickListener {
            closeDrawer()
            startActivity(Intent(this, ChatActivity::class.java))
        }
        findViewById<View>(R.id.btn_battery_opt).setOnClickListener {
            closeDrawer()
            requestBatteryOptimizations()
        }
        findViewById<View>(R.id.btn_connection_info).setOnClickListener {
            closeDrawer()
            showConnectionInfo()
        }
        findViewById<View>(R.id.btn_refresh).setOnClickListener {
            closeDrawer()
            deepRefresh()
        }
        findViewById<View>(R.id.btn_logs).setOnClickListener {
            closeDrawer()
            showLogs()
        }
        findViewById<View>(R.id.btn_wifi).setOnClickListener {
            closeDrawer()
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        findViewById<View>(R.id.btn_relay).setOnClickListener {
            closeDrawer()
            showTransportMode()
        }
        findViewById<View>(R.id.btn_pet_appearance).setOnClickListener {
            closeDrawer()
            showPetAppearance()
        }
    }

    /** 宠物外观切换：矢量旧形象 / 复古像素 / PNG素材 / 街机素材（业务零改动，仅渲染层切换）。 */
    private fun showPetAppearance() {
        val current = com.inklink.host.state.PetRenderMode.get(this)
        val options = arrayOf(
            "原版卡通（矢量）",
            "复古像素（掌机风）",
            "像素素材（PNG 宠物）",
            "街机像素（大屏素材）",
            "场景宠（云朵 LCD）"
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.pet_appearance)
            .setSingleChoiceItems(options, current.ordinal) { _, which ->
                val mode = com.inklink.host.state.PetRenderMode.values()[which]
                com.inklink.host.state.PetRenderMode.set(this, mode)
                Toast.makeText(
                    this,
                    when (mode) {
                        com.inklink.host.state.PetRenderMode.PIXEL_PNG -> "已切换为像素素材（缺帧自动回退程序化像素）"
                        com.inklink.host.state.PetRenderMode.PIXEL_RETRO -> "已切换为复古像素宠物"
                        com.inklink.host.state.PetRenderMode.VECTOR_OLD -> "已切换回原版卡通"
                        com.inklink.host.state.PetRenderMode.PIXEL_ARCADE_SPRITE -> "已切换为街机像素素材"
                        com.inklink.host.state.PetRenderMode.PIXEL_SCENE -> "已切换为云朵 LCD 场景宠"
                    },
                    Toast.LENGTH_SHORT
                ).show()
                // 宠物页 onResume 会自动应用新外观
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun requestBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm?.isIgnoringBatteryOptimizations(packageName) == false) {
                runCatching {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }.onFailure {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            } else {
                Toast.makeText(this, "已加入电池无限制白名单", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun closeDrawer() {
        drawerLayout.closeDrawer(GravityCompat.END)
    }

    private fun ensureServiceRunning() {
        val intent = Intent(this, InkForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun render() {
        val connected = hostState.connected
        idlePanel.visibility = if (connected) View.GONE else View.VISIBLE
        screenScroll.visibility = if (connected) View.VISIBLE else View.GONE
        tvStatus.text = getString(if (connected) R.string.status_connected else R.string.status_disconnected)

        bannerRinging.visibility = if (hostState.isRinging) View.VISIBLE else View.GONE

        val alert = hostState.pendingAlert
        if (alert != null) {
            tvAlertTitle.text = alert.title
            tvAlertContent.text = alert.content
            bannerAlert.visibility = View.VISIBLE
        } else {
            bannerAlert.visibility = View.GONE
        }

        tvScreenText.text = hostState.screenText.joinToString("\n")

        val image = hostState.currentImage
        if (image != null) {
            ivScreenImage.setImageBitmap(image)
            ivScreenImage.visibility = View.VISIBLE
        } else {
            ivScreenImage.visibility = View.GONE
        }

        if (!connected) {
            val app = application as InkHostApplication
            when {
                app.transportMode == InkHostApplication.MODE_ABLY -> {
                    tvIp.text = getString(R.string.ably_id_display, app.deviceId)
                    ivQr.visibility = View.GONE
                }
                !app.relayServerUrl.isNullOrBlank() -> {
                    tvIp.text = app.relayServerUrl
                    ivQr.visibility = View.GONE
                }
                else -> {
                    tvIp.text = getString(R.string.ip_display, hostState.localIp ?: "?", hostState.wsPort)
                    ivQr.visibility = View.VISIBLE
                    updateQr()
                }
            }
        }
    }

    private fun updateQr() {
        val ip = hostState.localIp ?: return
        val content = "ws://$ip:${hostState.wsPort}"
        if (content == lastQrContent) return
        lastQrContent = content
        generateQr(content)?.let { ivQr.setImageBitmap(it) }
    }

    private fun deepRefresh() {
        val app = application as InkHostApplication
        hostState.clearScreen()
        hostState.restoreScreenCache()
        app.transportManager.disconnect()
        ensureServiceRunning()
        Toast.makeText(this, R.string.deep_refresh_done, Toast.LENGTH_SHORT).show()
    }

    private fun showConnectionInfo() {
        val ip = hostState.localIp ?: "?"
        val port = hostState.wsPort
        val app = application as InkHostApplication
        AlertDialog.Builder(this)
            .setTitle(R.string.connection_info)
            .setMessage(getString(R.string.connection_info_content, ip, port, app.deviceId))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showLogs() {
        val logs = hostState.logs
        val text = if (logs.isEmpty()) getString(R.string.no_logs) else logs.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle(R.string.view_logs)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showTransportMode() {
        val app = application as InkHostApplication
        val modes = arrayOf(
            getString(R.string.mode_local),
            getString(R.string.mode_relay),
            getString(R.string.mode_ably),
            getString(
                if (app.pairingKeyIsDefault) R.string.pairing_settings_warning
                else R.string.pairing_settings
            )
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.transport_settings)
            .setItems(modes) { _, which ->
                when (which) {
                    0 -> {
                        app.transportMode = InkHostApplication.MODE_LOCAL
                        app.relayServerUrl = null
                        app.transportManager.switchMode(TransportMode.LOCAL, localPort = LocalWsTransport.DEFAULT_PORT)
                        render()
                        Toast.makeText(this, R.string.relay_cleared, Toast.LENGTH_SHORT).show()
                    }
                    1 -> showRelayDialog(app)
                    2 -> showAblyKeyDialog(app)
                    3 -> showPairingKeyDialog(app)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 配对密钥：派生 Ably 频道名与远程重置 PIN 的 HMAC 密钥；两端须设为一致。 */
    private fun showPairingKeyDialog(app: InkHostApplication) {
        val input = EditText(this).apply {
            hint = "配对密钥（主控端须填写相同值）"
            setText(app.pairingKey)
        }
        AlertDialog.Builder(this)
            .setTitle("配对密钥设置")
            .setMessage(
                if (app.pairingKeyIsDefault)
                    "⚠️ 当前为公开源码默认密钥，任何人可猜出频道名并伪造远程重置 PIN 指令。请改为私有值，并让主控端填写相同密钥。"
                else null
            )
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                app.pairingKey = input.text.toString()
                if (app.transportMode == InkHostApplication.MODE_ABLY) {
                    app.transportManager.switchMode(
                        TransportMode.ABLY,
                        ablyKey = app.ablyKey,
                        ablyChannel = "${com.inklink.common.transport.AblyRelayTransport.CHANNEL_PREFIX}${app.pairingKey}"
                    )
                }
                render()
                Toast.makeText(this, "配对密钥已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 运行时填写 Ably Key(公开源码构建内置为空,必须在此填写);留空=回落内置值。 */
    private fun showAblyKeyDialog(app: InkHostApplication) {
        val input = EditText(this).apply {
            hint = "Ably Root Key(留空=使用内置)"
            setText(app.ablyKey)
        }
        AlertDialog.Builder(this)
            .setTitle("Ably 密钥设置")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val key = input.text.toString().trim()
                app.ablyKey = key
                app.transportMode = InkHostApplication.MODE_ABLY
                app.transportManager.switchMode(
                    TransportMode.ABLY,
                    ablyKey = app.ablyKey,
                    ablyChannel = "${com.inklink.common.transport.AblyRelayTransport.CHANNEL_PREFIX}${app.pairingKey}"
                )
                render()
                Toast.makeText(
                    this,
                    if (key.isBlank()) "已使用内置 Ably Key 连接" else "Ably Key 已保存并连接",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRelayDialog(app: InkHostApplication) {
        val input = EditText(this).apply {
            hint = getString(R.string.relay_hint)
            setText(app.relayServerUrl.orEmpty())
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.relay_settings)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotBlank()) {
                    app.transportMode = InkHostApplication.MODE_RELAY
                    app.relayServerUrl = url
                    app.transportManager.switchMode(TransportMode.RELAY, relayServerUrl = url)
                    render()
                    Toast.makeText(this, R.string.relay_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.relay_url_required, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun generateQr(content: String): Bitmap? = runCatching {
        val size = DensityUtil.dp2px(this, 160f)
        val matrix: BitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        bmp
    }.getOrNull()

    private fun setupFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }

    private fun applyRoundScreenInset() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.setOnApplyWindowInsetsListener { _, insets ->
                val isRound = insets.isRound
                val inset = if (isRound) DensityUtil.dp2px(this, 20f) else DensityUtil.dp2px(this, 8f)
                findViewById<View>(R.id.main_content_container)?.setPadding(inset, inset, inset, inset)
                insets
            }
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 100
    }
}
