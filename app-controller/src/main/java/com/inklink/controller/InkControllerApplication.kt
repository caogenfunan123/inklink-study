package com.inklink.controller

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.inklink.common.audio.AudioManager
import com.inklink.common.chat.ChatStore
import com.inklink.common.protocol.ChatMessage
import com.inklink.common.protocol.InkMessage
import com.inklink.common.protocol.MessageType
import com.inklink.common.protocol.payload.AckPayload
import com.inklink.common.protocol.payload.AlertPushPayload
import com.inklink.common.protocol.payload.DeviceStatusPayload
import com.inklink.common.protocol.payload.HeartbeatPayload
import com.inklink.common.protocol.payload.RingPayload
import com.inklink.common.service.geofence.GeofenceConfig
import com.inklink.common.service.gps.GpsManager
import com.inklink.common.service.gps.GpsReport
import com.inklink.common.transport.LocalWsTransport
import com.inklink.common.transport.TransportListener
import com.inklink.common.transport.TransportManager
import com.inklink.common.transport.TransportMode
import com.inklink.common.utils.DeviceIdProvider
import com.inklink.common.utils.HeartbeatPolicy
import com.inklink.common.utils.PermissionUtil
import com.inklink.controller.BuildConfig
import com.inklink.controller.data.DeviceEntity
import com.inklink.controller.data.DeviceRepository
import com.inklink.controller.state.AlertInfo
import com.inklink.controller.state.ControllerState
import com.tencent.tencentmap.mapsdk.maps.TencentMapInitializer

/**
 * 主控端 Application。
 *
 * 持有传输管理器与语音管理器（进程级存活，Activity 切换不中断），
 * 统一分发收到的消息：GPS 与告警写入 [ControllerState]，语音帧交给 [AudioManager]。
 */
class InkControllerApplication : Application() {

    lateinit var transportManager: TransportManager
        private set

    lateinit var deviceId: String
        private set

    val controllerState = ControllerState()
    val audioManager = AudioManager()
    val chatStore = ChatStore()

    /** 看护提醒（阶段五）：离线 30min / 连续在线 2h 本地通知。 */
    val careMonitor by lazy { com.inklink.controller.care.CareMonitor(this) }
    lateinit var deviceRepository: DeviceRepository
        private set

    /** 历史轨迹本地存储：GPS_REPORT 按设备按天落盘 JSONL，供地图回放/GPX 导出。 */
    val trackStore by lazy { com.inklink.controller.data.TrackStore(this) }

    private val gson = Gson()
    private var inCall = false

    /** 主控端自身定位采集（地图上绿色标记）。 */
    private var selfGpsManager: GpsManager? = null

    override fun onCreate() {
        super.onCreate()
        // 腾讯地图隐私合规：6.x 版本需在地图初始化前同意隐私协议并启动 SDK
        TencentMapInitializer.setAgreePrivacy(this, true)
        TencentMapInitializer.start(this)
        deviceId = DeviceIdProvider.getDeviceId(this)
        deviceRepository = DeviceRepository(this)
        transportManager = TransportManager(
            deviceId = deviceId,
            localRole = LocalWsTransport.LocalRole.CLIENT
        )
        transportManager.heartbeatIntervalProvider = { HeartbeatPolicy.interval(this) }
        transportManager.setListener(object : TransportListener {
            override fun onTextMessage(message: InkMessage) = route(message)
            override fun onAudioMessage(frame: ByteArray) = audioManager.play(frame)
            override fun onConnectionChanged(connected: Boolean) = controllerState.setConnected(connected)
        })
        createNotificationChannel()
        careMonitor.start()

        // 默认走 Ably 中转（4G-4G），启动即连接，收到所有受控端 GPS 上报
        connectAbly()
    }

    /** 局域网模式连接受控端。 */
    fun connectLocal(host: String, port: Int) {
        transportManager.defaultTargetDeviceId = null
        transportManager.switchMode(TransportMode.LOCAL, localHost = host, localPort = port)
    }

    /** 公网中转模式，定向到指定受控端设备。 */
    fun switchRelay(serverUrl: String, targetDeviceId: String) {
        transportManager.defaultTargetDeviceId = targetDeviceId
        transportManager.switchMode(TransportMode.RELAY, relayServerUrl = serverUrl)
    }

    /** Ably 中转模式（临时原型，4G-4G），定向到指定受控端设备。 */
    fun switchAbly(targetDeviceId: String) {
        controllerState.selectDevice(targetDeviceId)
        transportManager.defaultTargetDeviceId = targetDeviceId
        switchAblyChannel()
    }

    /** Ably 中转模式：连接 Ably 频道，定向目标由 [selectDevice] 决定。 */
    fun connectAbly() {
        transportManager.defaultTargetDeviceId = controllerState.selectedDeviceId
        switchAblyChannel()
    }

    /** 配对密钥，与受控端约定一致（默认 inklink_default_key），用于派生 Ably 私有频道名。 */
    val pairingKey: String
        get() = getSharedPreferences("inklink_controller", MODE_PRIVATE)
            .getString("pairing_key", "inklink_default_key") ?: "inklink_default_key"

    /**
     * Ably Root Key:家长端"Ably 密钥"弹窗填写优先(存 prefs),否则回落 BuildConfig
     * (本地/CI 注入;公开源码构建时为空,必须填写)。
     */
    var ablyKey: String
        get() = getSharedPreferences("inklink_controller", MODE_PRIVATE)
            .getString("ably_key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.ABLY_KEY
        set(value) {
            getSharedPreferences("inklink_controller", MODE_PRIVATE)
                .edit().putString("ably_key", value).apply()
        }

    /**
     * 连接 Ably 私有配对频道 inklink-pet-${pairingKey}。
     * pairingKey 与受控端一致（默认 inklink_default_key），隔离不同配对设备。
     */
    private fun switchAblyChannel() {
        val key = ablyKey
        transportManager.switchMode(
            TransportMode.ABLY,
            ablyKey = key,
            ablyChannel = "${com.inklink.common.transport.AblyRelayTransport.CHANNEL_PREFIX}$pairingKey"
        )
    }

    /** 启动主控端自身定位采集（地图页展示绿色标记），幂等。 */
    fun startSelfLocation() {
        if (selfGpsManager != null) return
        selfGpsManager = GpsManager(this) { lat, lng, speed, time ->
            controllerState.setSelfLocation(GpsReport(lat, lng, speed, time))
        }
        selfGpsManager?.start()
    }

    /** 广播或定向请求受控端立即上报一次位置。 */
    fun requestGps(targetDeviceId: String? = controllerState.selectedDeviceId) {
        transportManager.sendMessage(InkMessage.control(MessageType.REQUEST_GPS, from = deviceId, target = targetDeviceId))
    }

    /** 向指定受控端下发强控响铃指令，返回生成的 msgId。 */
    fun sendRing(targetDeviceId: String?, durationSec: Int = 15, volumePct: Int = 100): String {
        val payload = gson.toJson(RingPayload(durationSec, volumePct))
        val msg = InkMessage.text(MessageType.CMD_RING, payload, from = deviceId, target = targetDeviceId)
        transportManager.sendMessage(msg)
        targetDeviceId?.let { controllerState.setDeviceRinging(it, true) }
        return msg.msgId
    }

    /** 向指定受控端下发停止响铃指令。 */
    fun sendStopRing(targetDeviceId: String?): String {
        val msg = InkMessage.control(MessageType.CMD_STOP_RING, from = deviceId, target = targetDeviceId)
        transportManager.sendMessage(msg)
        targetDeviceId?.let { controllerState.setDeviceRinging(it, false) }
        return msg.msgId
    }

    /**
     * 下发宠物行为事件 CMD_PET_EVENT(23)：受控端必定结算状态 + 播预制音效，
     * 文字播报按 [AudioSettings] 全局开关决定是否携带（§六）。返回 msgId 便于 ACK 关联。
     */
    fun sendPetEvent(targetDeviceId: String?, eventId: String, ttsText: String = ""): String {
        val text = com.inklink.controller.audio.AudioSettings.ttsTextFor(this, ttsText)
        val payload = gson.toJson(
            com.inklink.common.protocol.payload.PetEventPayload(
                eventId = eventId,
                ttsText = text,
                enableTts = text.isNotEmpty()
            )
        )
        val msg = InkMessage.text(MessageType.CMD_PET_EVENT, payload, from = deviceId, target = targetDeviceId)
        transportManager.sendMessage(msg)
        return msg.msgId
    }

    /** 下发通用提示音 CMD_PLAY_SOUND(22)：只出声不动数值。 */
    fun playHostSound(targetDeviceId: String?, soundId: String, ttsText: String = ""): String {
        val text = com.inklink.controller.audio.AudioSettings.ttsTextFor(this, ttsText)
        val payload = gson.toJson(
            com.inklink.common.protocol.payload.PlaySoundPayload(
                soundId = soundId,
                ttsText = text,
                enableTts = text.isNotEmpty()
            )
        )
        val msg = InkMessage.text(MessageType.CMD_PLAY_SOUND, payload, from = deviceId, target = targetDeviceId)
        transportManager.sendMessage(msg)
        return msg.msgId
    }

    /** 向指定受控端推送告警（系统通知 + 悬浮横幅，可选强制响铃）。 */
    fun sendPushAlert(
        targetDeviceId: String?,
        title: String,
        content: String,
        ring: Boolean = false,
        durationSec: Int = 15,
        needNotification: Boolean = true
    ): String {
        val payload = gson.toJson(
            AlertPushPayload(
                title = title,
                content = content,
                ring = ring,
                durationSec = durationSec,
                needNotification = needNotification
            )
        )
        val msg = InkMessage.text(MessageType.PUSH_ALERT, payload, from = deviceId, target = targetDeviceId)
        transportManager.sendMessage(msg)
        if (ring) {
            targetDeviceId?.let { controllerState.setDeviceRinging(it, true) }
        }
        return msg.msgId
    }

    /** 触发受控端全屏深度刷新与残影消除。 */
    fun sendDeepRefresh(targetDeviceId: String?): String {
        val msg = InkMessage.control(MessageType.REFRESH_SCREEN_DEEP, from = deviceId, target = targetDeviceId)
        transportManager.sendMessage(msg)
        return msg.msgId
    }

    fun sendChatText(text: String) {
        transportManager.sendMessage(InkMessage.text(MessageType.CHAT_TEXT, text, from = deviceId))
        chatStore.add(ChatMessage(chatStore.nextId(), MessageType.CHAT_TEXT, text, deviceId, System.currentTimeMillis()))
    }

    fun sendChatImage(base64: String) {
        transportManager.sendMessage(InkMessage.text(MessageType.CHAT_IMAGE, base64, from = deviceId))
        chatStore.add(ChatMessage(chatStore.nextId(), MessageType.CHAT_IMAGE, base64, deviceId, System.currentTimeMillis()))
    }

    fun sendChatAudio(base64: String) {
        transportManager.sendMessage(InkMessage.text(MessageType.CHAT_AUDIO, base64, from = deviceId))
        chatStore.add(ChatMessage(chatStore.nextId(), MessageType.CHAT_AUDIO, base64, deviceId, System.currentTimeMillis()))
    }

    /** 选中目标设备，后续定向消息/围栏下发到该设备。 */
    fun selectDevice(deviceId: String?) {
        transportManager.defaultTargetDeviceId = deviceId
        controllerState.selectDevice(deviceId)
    }

    fun devices(): List<DeviceEntity> = deviceRepository.list()

    fun addDevice(deviceId: String, nickname: String) =
        deviceRepository.add(DeviceEntity(deviceId, nickname))

    fun removeDevice(deviceId: String) {
        deviceRepository.remove(deviceId)
        if (controllerState.selectedDeviceId == deviceId) {
            selectDevice(null)
        }
    }

    fun updateDeviceNickname(deviceId: String, nickname: String) =
        deviceRepository.updateNickname(deviceId, nickname)

    fun disconnect() {
        transportManager.disconnect()
        controllerState.setConnected(false)
    }

    /** 发起语音通话。 */
    fun startCall() {
        if (inCall) return
        if (!PermissionUtil.hasPermissions(this, PermissionUtil.RECORD_AUDIO)) {
            controllerState.setAlert(AlertInfo("无录音权限", 0.0, 0.0, 0.0, System.currentTimeMillis()))
            return
        }
        inCall = true
        controllerState.setInCall(true)
        transportManager.sendMessage(InkMessage.control(MessageType.AUDIO_START, from = deviceId))
    }

    /** 挂断语音通话。 */
    fun stopCall() {
        if (!inCall) return
        inCall = false
        controllerState.setInCall(false)
        audioManager.stopRecord()
        transportManager.sendMessage(InkMessage.control(MessageType.AUDIO_STOP, from = deviceId))
    }

    private fun route(message: InkMessage) {
        // 任何来自受控端的消息（GPS/告警/心跳等）都视为其在线信号，刷新最近活跃时间
        message.fromDeviceId?.let { controllerState.markSeen(it) }
        when (message.messageType) {
            MessageType.GPS_REPORT ->
                runCatching { gson.fromJson(message.payload, GpsReport::class.java) }
                    .getOrNull()?.let { gps ->
                        val from = message.fromDeviceId ?: return@let
                        // 首次收到某受控端上报时自动登记，便于用户直接定向投屏/围栏
                        if (deviceRepository.findByDeviceId(from) == null) {
                            deviceRepository.add(DeviceEntity(from, ""))
                        }
                        controllerState.setGps(from, gps)
                        // 历史轨迹落盘（低质量点在 TrackStore 内过滤）
                        trackStore.append(from, gps)
                    }
            MessageType.DEVICE_STATUS_REPORT ->
                runCatching { gson.fromJson(message.payload, DeviceStatusPayload::class.java) }
                    .getOrNull()?.let { status ->
                        val from = message.fromDeviceId ?: return@let
                        controllerState.setDeviceStatus(from, status)
                        controllerState.setDeviceRinging(from, status.isRinging)
                    }
            MessageType.CMD_ACK ->
                runCatching { gson.fromJson(message.payload, AckPayload::class.java) }
                    .getOrNull()?.let { ack ->
                        controllerState.dispatchAck(ack)
                    }
            MessageType.HEARTBEAT -> {
                val from = message.fromDeviceId
                if (!from.isNullOrBlank()) {
                    careMonitor.onPeerAlive(from)
                    runCatching { gson.fromJson(message.payload, HeartbeatPayload::class.java) }
                        .getOrNull()?.let { hb ->
                            val currentStatus = controllerState.statusByDevice[from]
                            val status = currentStatus?.copy(batteryPct = hb.batteryPct)
                                ?: DeviceStatusPayload(batteryPct = hb.batteryPct)
                            controllerState.setDeviceStatus(from, status)
                        }
                }
            }
            MessageType.PING -> {
                // 心跳应答：受控端每 30s 发 PING(50)，45s 未收到 PONG 判定离线
                message.fromDeviceId?.let { careMonitor.onPeerAlive(it) }
                transportManager.sendMessage(
                    InkMessage.control(MessageType.PONG, from = deviceId, target = message.fromDeviceId)
                )
            }
            MessageType.ALERT_ENTER -> handleAlert("进入围栏", message)
            MessageType.ALERT_EXIT -> handleAlert("离开围栏", message)
            MessageType.PET_ALERT_EVENT -> handlePetAlert(message)
            MessageType.LEARN_PROGRESS -> handleLearnProgress(message)
            MessageType.AUDIO_START -> onAudioStartConfirmed()
            MessageType.AUDIO_STOP -> onAudioStopReceived(message.payload)
            MessageType.CHAT_TEXT, MessageType.CHAT_IMAGE, MessageType.CHAT_AUDIO ->
                message.messageType?.let { type ->
                    message.payload?.let { payload ->
                        chatStore.add(
                            ChatMessage(
                                chatStore.nextId(), type, payload,
                                message.fromDeviceId, System.currentTimeMillis()
                            )
                        )
                    }
                }
            else -> Unit
        }
    }

    private fun onAudioStartConfirmed() {
        // 受控端确认通话，主控端开始采集（AUDIO_DATA 已在 onAudioMessage 播放）
        audioManager.startRecord { frame -> transportManager.sendAudio(frame.toFrame()) }
    }

    private fun onAudioStopReceived(reason: String?) {
        inCall = false
        controllerState.setInCall(false)
        audioManager.stopRecord()
        val text = when (reason) {
            "NO_PERMISSION" -> "受控端无录音权限，通话被拒绝"
            "BUSY" -> "受控端忙线"
            else -> "通话结束"
        }
        controllerState.setAlert(AlertInfo(text, 0.0, 0.0, 0.0, System.currentTimeMillis()))
    }

    /** 学习进度上报(47):解析后落 ControllerState,Dashboard 设备卡显示学习简报。 */
    private fun handleLearnProgress(message: InkMessage) {
        val from = message.fromDeviceId ?: return
        val payload = runCatching {
            gson.fromJson(message.payload, com.inklink.common.protocol.payload.LearnProgressPayload::class.java)
        }.getOrNull() ?: return
        val moduleLabel = when (payload.module) {
            "HANZI" -> "识字"
            "PINYIN" -> "拼音"
            "MATH" -> "口算"
            "POEM" -> "古诗"
            "ENGLISH" -> "英语"
            else -> "学习"
        }
        controllerState.setLearnSummary(
            from,
            "📚 学习简报:刚完成 L${payload.level} $moduleLabel ${payload.itemsDone}题 " +
                "正确率${(payload.correctRate * 100).toInt()}% · 今日${payload.minutesToday}分钟/${payload.coinsToday}金币" +
                if (payload.weakTop5.isEmpty()) "" else " · 薄弱:${payload.weakTop5.joinToString("")}"
        )
    }

    private fun handlePetAlert(message: InkMessage) {
        val payload = runCatching {
            gson.fromJson(message.payload, com.inklink.common.protocol.payload.PetAlertEventPayload::class.java)
        }.getOrNull()
        val desc = payload?.description ?: "受控端宠物受到惊吓报警"
        val info = AlertInfo(
            desc,
            0.0,
            0.0,
            0.0,
            System.currentTimeMillis(),
            message.fromDeviceId
        )
        controllerState.setAlert(info)
        // 围栏离开事件受控端会同时发 PET_ALERT_EVENT 与 ALERT_EXIT，系统通知只发后者，避免重复弹两条
        if (payload?.alertType != "GEOFENCE_EXIT") {
            sendAlertNotification(desc, null, message.fromDeviceId)
        }
    }

    private fun handleAlert(type: String, message: InkMessage) {
        val cfg = runCatching { gson.fromJson(message.payload, GeofenceConfig::class.java) }.getOrNull()
        val info = AlertInfo(
            type,
            cfg?.lat ?: 0.0,
            cfg?.lng ?: 0.0,
            cfg?.radius ?: 0.0,
            System.currentTimeMillis(),
            message.fromDeviceId
        )
        controllerState.setAlert(info)
        sendAlertNotification(type, cfg, message.fromDeviceId)
    }

    /** 围栏/宠物告警系统通知，点击直达地图并聚焦告警设备。 */
    private fun sendAlertNotification(type: String, cfg: GeofenceConfig?, deviceId: String?) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionUtil.hasPermissions(this, android.Manifest.permission.POST_NOTIFICATIONS)
        ) {
            return
        }
        val name = deviceId?.let { id ->
            devices().firstOrNull { it.deviceId == id }?.nickname?.takeIf { it.isNotBlank() } ?: id.take(8)
        } ?: "受控端"
        val text = buildString {
            append(name)
            cfg?.let { append(" 位置: ${it.lat}, ${it.lng}（半径 ${it.radius}m）") }
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            // requestCode 绑定设备：多设备先后告警时各自持有 PendingIntent，
            // 避免同 requestCode+相同 Intent 导致 extras 被覆盖、点击聚焦错设备
            (deviceId ?: "").hashCode(),
            Intent(this, com.inklink.controller.ui.MapActivity::class.java)
                .putExtra(EXTRA_FOCUS_DEVICE, deviceId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("围栏告警：$type")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "围栏告警",
                NotificationManager.IMPORTANCE_HIGH
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val ALERT_CHANNEL_ID = "inklink_alert"

        /** 告警通知点击跳转 MapActivity 时聚焦的设备 ID extra。 */
        const val EXTRA_FOCUS_DEVICE = "extra_focus_device"
    }
}
