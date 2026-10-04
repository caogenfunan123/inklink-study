package com.inklink.host.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.gson.Gson
import com.inklink.common.audio.AudioManager
import com.inklink.common.protocol.ChatMessage
import com.inklink.common.protocol.InkMessage
import com.inklink.common.protocol.MessageType
import com.inklink.common.protocol.payload.AckPayload
import com.inklink.common.protocol.payload.AlertPushPayload
import com.inklink.common.protocol.payload.DeviceStatusPayload
import com.inklink.common.protocol.payload.RingPayload
import com.inklink.common.service.discovery.UdpDiscovery
import com.inklink.common.service.geofence.FenceEvent
import com.inklink.common.service.geofence.GeoFenceManager
import com.inklink.common.service.geofence.GeofenceConfig
import com.inklink.common.service.gps.GpsManager
import com.inklink.common.service.gps.GpsReport
import com.inklink.common.transport.LocalWsTransport
import com.inklink.common.transport.TransportListener
import com.inklink.common.transport.TransportManager
import com.inklink.common.transport.TransportMode
import com.inklink.common.utils.HeartbeatPolicy
import com.inklink.common.utils.IdempotentController
import com.inklink.common.utils.MonoThrottle
import com.inklink.common.utils.ImageUtil
import com.inklink.common.utils.NetworkUtil
import com.inklink.common.utils.PermissionUtil
import com.inklink.common.utils.ReportThrottler
import com.inklink.host.InkHostApplication
import com.inklink.host.R
import com.inklink.host.state.HostAlert
import com.inklink.host.state.HostScreenState
import com.inklink.host.ui.HostActivity

/**
 * 受控端前台服务。
 *
 * 托管 GPS 采集、围栏监测、强控响铃、保活巡检与画面自愈。
 */
class InkForegroundService : Service() {

    private lateinit var transportManager: TransportManager
    private lateinit var gpsManager: GpsManager
    private lateinit var hostState: HostScreenState
    private lateinit var deviceId: String

    private val geofenceManager = GeoFenceManager(confirmCount = 2)
    private val geofenceStore by lazy { GeofenceStore(this) }
    private val audioManager = AudioManager()
    private val gson = Gson()
    private var inCall = false
    private lateinit var sirenManager: SirenManager
    private var audioFeedback: com.inklink.host.audio.PetAudioFeedback? = null
    /** 饥饿提醒冷却。[MonoThrottle] 用单调时钟，墙钟回拨会让 `now-last` 为负而**永久不再提醒**。 */
    private val hungerAlertThrottle = MonoThrottle(HUNGER_ALERT_GAP_MS)
    private val idempotentController = IdempotentController()
    /**
     * 跨类型语义幂等：23 的 event_feed 与 31 的 FEED 是同一件事，而 msgId 不同，
     * msgId 去重挡不住「主控端先用 31 再用 23 各发一次」→ 用 eventId+triggerTs 兜一层。
     */
    private val eventSemanticGuard = IdempotentController(maxCapacity = 64, ttlMs = 10_000L)
    private val reportThrottler = ReportThrottler(minLocationDistanceMeters = 8.0, minLocationIntervalMs = 5_000L)
    private val trackLogger by lazy { com.inklink.host.data.GpsTrackLogger(this) }
    private val historyServer by lazy { HistoryServer(trackLogger) }

    /** 历史补传专用单线程（串行化请求处理，内部有逐块发送间隔，禁止跑主线程）。 */
    private val historyExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var treasureHunter: com.inklink.host.state.GpsTreasureHunter
    private val offlineEventQueue by lazy { com.inklink.host.queue.OfflineEventQueue(this) }
    private var pingJob: kotlinx.coroutines.Job? = null
    private var lastPongTs: Long = System.currentTimeMillis()
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

    private var powerManager: PowerManager? = null
    private var alarmManager: AlarmManager? = null
    private var alarmPendingIntent: PendingIntent? = null

    private val controllerCommandTypes = setOf(
        MessageType.TEXT, MessageType.IMAGE, MessageType.CMD_CLEAR,
        MessageType.GEOFENCE_CONFIG, MessageType.AUDIO_START, MessageType.AUDIO_STOP,
        MessageType.REQUEST_GPS, MessageType.CMD_RING, MessageType.CMD_STOP_RING,
        MessageType.REFRESH_SCREEN_DEEP, MessageType.SCREEN_CACHE_RESTORE,
        MessageType.CMD_PLAY_SOUND, MessageType.CMD_PET_EVENT
    )

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { updateBatteryState(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        val app = application as InkHostApplication
        transportManager = app.transportManager
        hostState = app.hostState
        deviceId = app.deviceId
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager

        treasureHunter = com.inklink.host.state.GpsTreasureHunter { coinGain, expGain ->
            val petManager = com.inklink.host.state.PetStateManager(this)
            // 统一走奖励入账：含升级与生命周期推进，并落盘
            petManager.addReward(coinGain, expGain)
            _petEventFlow.tryEmit(PetUiEvent.TreasureReward(coinGain, expGain))
        }

        sirenManager = SirenManager(this).apply {
            onStateChanged = { ringing ->
                hostState.setRinging(ringing)
                if (ringing) {
                    hostState.appendLog("收到强制响铃指令")
                    _petEventFlow.tryEmit(PetUiEvent.AlertTriggered)
                } else {
                    hostState.appendLog("停止响铃")
                    _petEventFlow.tryEmit(PetUiEvent.AlertDismissed)
                }
                broadcastDeviceStatus()
            }
        }

        transportManager.heartbeatIntervalProvider = { HeartbeatPolicy.interval(this) }
        transportManager.setListener(transportListener)

        connectTransport()
        startPingHeartbeatLoop()

        hostState.setLocalIp(NetworkUtil.getLocalIpv4Address())
        hostState.setWsPort(LocalWsTransport.DEFAULT_PORT)

        // 音频系统：启动即做一次 TTS 可用性探测（§七.3），结果落布尔标记并随状态上报主控
        audioFeedback = com.inklink.host.audio.PetAudioFeedback(this).also { fb ->
            fb.start()
            com.inklink.host.audio.PetTtsGate.get(this).addProbeListener { probe ->
                if (probe != com.inklink.host.audio.PetTtsGate.Probe.UNKNOWN) {
                    hostState.appendLog(
                        if (probe == com.inklink.host.audio.PetTtsGate.Probe.READY)
                            "TTS 引擎可用，支持文字播报"
                        else "设备无可用 TTS 引擎，文字播报已自动关闭（预制音效不受影响）"
                    )
                    broadcastDeviceStatus()
                }
            }
        }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        setupAlarmPing()

        hostState.appendLog("前台服务启动")
    }

    private fun connectTransport() {
        val app = application as InkHostApplication
        when (app.transportMode) {
            InkHostApplication.MODE_ABLY ->
                transportManager.switchMode(
                    TransportMode.ABLY,
                    ablyKey = app.ablyKey,
                    ablyChannel = "${com.inklink.common.transport.AblyRelayTransport.CHANNEL_PREFIX}${app.pairingKey}"
                )
            InkHostApplication.MODE_RELAY -> {
                val relayUrl = app.relayServerUrl
                if (!relayUrl.isNullOrBlank()) {
                    transportManager.switchMode(TransportMode.RELAY, relayServerUrl = relayUrl)
                } else {
                    transportManager.switchMode(TransportMode.LOCAL, localPort = LocalWsTransport.DEFAULT_PORT)
                }
            }
            else ->
                transportManager.switchMode(TransportMode.LOCAL, localPort = LocalWsTransport.DEFAULT_PORT)
        }
        setupFriendChannels(app)
    }

    /**
     * 好友社交（仅 Ably 模式）：发布路由指向好友频道、Presence 上下线入缓存、挂载全部好友频道。
     */
    private fun setupFriendChannels(app: InkHostApplication) {
        val ably = transportManager.currentTransport() as? com.inklink.common.transport.AblyRelayTransport ?: return
        val repo = app.friendRepository
        ably.targetChannelResolver = { targetDeviceId ->
            repo.findByDeviceId(targetDeviceId)?.let { repo.channelNameOf(it) }
        }
        ably.presenceListener = { channelName, clientId, online ->
            repo.findByChannel(channelName)?.let { friend ->
                repo.bindDeviceId(friend.pairingKey, clientId)
                if (online) repo.onlineByDeviceId[clientId] = true else repo.onlineByDeviceId.remove(clientId)
            }
        }
        repo.getFriends().forEach { friend -> ably.attachChannel(repo.channelNameOf(friend)) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 权限补授后再次 startService 可升级前台服务类型（按已授权限重新计算）
        runCatching { startAsForeground() }
        startModules()
        acquireTemporaryWakeLock(2000L)
        return START_STICKY
    }

    private fun startModules() {
        if (!::gpsManager.isInitialized) {
            gpsManager = GpsManager(this) { report ->
                onGpsLocation(report)
            }
        }
        gpsManager.start()
        // 历史轨迹补传：分块发送通道绑定（定向回传给请求发起的主控端）
        historyServer.bindSender { target, chunk ->
            transportManager.sendMessage(
                InkMessage.text(MessageType.HISTORY_CHUNK, gson.toJson(chunk), from = deviceId, target = target)
            )
        }
        // 恢复上次下发的围栏（GeoFenceManager 仅内存，重启不丢配置）
        geofenceStore.load()?.let { payload ->
            runCatching {
                val cfg = gson.fromJson(payload, GeofenceConfig::class.java)
                val fences = cfg.toGeoFences()
                geofenceManager.updateFences(fences)
                hostState.appendLog("恢复围栏 ${fences.size} 个: lat=${cfg.lat}, lng=${cfg.lng}, r=${cfg.radius}m")
            }
        }
        UdpDiscovery.startResponder(deviceId, LocalWsTransport.DEFAULT_PORT)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pingJob?.cancel()
        runCatching { unregisterReceiver(batteryReceiver) }
        cancelAlarmPing()
        if (::gpsManager.isInitialized) gpsManager.stop()
        if (::sirenManager.isInitialized) sirenManager.stopRing()
        audioManager.release()
        // 只放播放器（MediaPlayer）；TTS 单例留给 UI/广播接收器，服务重建时不必重新 bind 引擎
        audioFeedback?.stopAll()
        audioFeedback = null
        // 历史补传执行器与轨迹落盘线程停掉，防服务反复重建时线程泄漏
        historyExecutor.shutdown()
        // 轨迹内存缓冲强制落盘，避免丢最后 30s 数据
        runCatching { trackLogger.close() }
        UdpDiscovery.stopResponder()
        transportManager.disconnect()
        hostState.appendLog("前台服务停止")
        super.onDestroy()
    }

    private val transportListener = object : TransportListener {
        override fun onTextMessage(message: InkMessage) {
            acquireTemporaryWakeLock(2000L)
            message.fromDeviceId
                ?.takeIf { it.isNotBlank() && it != deviceId && message.messageType in controllerCommandTypes }
                ?.let { transportManager.defaultTargetDeviceId = it }
            routeTextMessage(message)
        }

        override fun onAudioMessage(frame: ByteArray) {
            audioManager.play(frame)
        }

        override fun onConnectionChanged(connected: Boolean) {
            hostState.setConnected(connected)
            hostState.appendLog(if (connected) "主控端已连接" else "主控端已断开")
            if (connected) {
                lastPongTs = System.currentTimeMillis()
                broadcastDeviceStatus()
                flushOfflineEvents()
            }
        }
    }

    private fun routeTextMessage(message: InkMessage) {
        when (message.messageType) {
            MessageType.TEXT -> {
                sendAck(message.msgId, message.fromDeviceId, AckPayload.CODE_OK)
                message.payload?.let { hostState.appendText(it) }
            }
            MessageType.IMAGE -> {
                sendAck(message.msgId, message.fromDeviceId, AckPayload.CODE_OK)
                message.payload?.let {
                    ImageUtil.decodeBase64(it)?.let { bmp -> hostState.setImage(bmp) }
                }
            }
            MessageType.CMD_CLEAR -> {
                sendAck(message.msgId, message.fromDeviceId, AckPayload.CODE_OK)
                hostState.clearScreen()
            }
            MessageType.GEOFENCE_CONFIG -> {
                applyGeofence(message.payload)
                sendAck(message.msgId, message.fromDeviceId, AckPayload.CODE_OK)
            }
            MessageType.AUDIO_START -> onAudioStart()
            MessageType.AUDIO_STOP -> onAudioStop()
            MessageType.REQUEST_GPS -> onRequestGps()
            MessageType.CMD_RING -> onCommandRing(message)
            MessageType.CMD_STOP_RING -> onCommandStopRing(message)
            MessageType.HISTORY_REQUEST -> onHistoryRequest(message)
            MessageType.HISTORY_ACK -> {
                runCatching {
                    gson.fromJson(message.payload, com.inklink.common.protocol.payload.HistoryAckPayload::class.java)
                }.getOrNull()?.let { historyServer.onAck(it.reqId) }
            }
            MessageType.PUSH_ALERT -> onPushAlert(message)
            MessageType.PET_INTERACT_CMD -> onPetInteractCommand(message)
            MessageType.CMD_PLAY_SOUND -> onPlaySoundCommand(message)
            MessageType.CMD_PET_EVENT -> onPetEventCommand(message)
            MessageType.PET_STATE_SYNC -> onPetStateSyncRequest(message)
            MessageType.PET_BAG_SYNC -> onPetBagSyncRequest(message)
            MessageType.PET_REMOTE_GIFT -> onPetRemoteGift(message)
            MessageType.PET_GAME_INVITE -> onPetGameInvite(message)
            MessageType.PET_GAME_ACTION -> onPetGameAction(message)
            MessageType.PET_BAG_INTERACT -> onPetBagInteract(message)
            MessageType.CMD_RESET_HOST_PIN -> onResetHostPinCommand(message)
            MessageType.REMOTE_TASK_SEND -> onRemoteTaskSend(message)
            MessageType.REFRESH_SCREEN_DEEP -> {
                hostState.clearScreen()
                sendAck(message.msgId, message.fromDeviceId, AckPayload.CODE_OK)
                hostState.appendLog("执行全屏深度刷新")
            }
            MessageType.SCREEN_CACHE_RESTORE -> {
                val restored = hostState.restoreScreenCache()
                val code = if (restored) AckPayload.CODE_OK else AckPayload.CODE_PERMISSION_DENIED
                sendAck(message.msgId, message.fromDeviceId, code, if (restored) "" else "无有效画面缓存")
            }
            MessageType.CHAT_TEXT, MessageType.CHAT_IMAGE, MessageType.CHAT_AUDIO -> {
                val app = application as InkHostApplication
                message.messageType?.let { type ->
                    message.payload?.let { payload ->
                        app.chatStore.add(
                            ChatMessage(
                                app.chatStore.nextId(), type, payload,
                                message.fromDeviceId, System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
            MessageType.HEARTBEAT, MessageType.PONG -> {
                lastPongTs = System.currentTimeMillis()
                if (!hostState.connected) {
                    hostState.setConnected(true)
                }
            }
            else -> Unit
        }
    }

    private fun onCommandRing(message: InkMessage) {
        if (!idempotentController.checkAndRecord(message.msgId)) {
            sendAck(message.msgId, message.fromDeviceId, AckPayload.CODE_IDEMPOTENT_DROP, "重复响铃指令已忽略")
            return
        }
        val ringPayload = runCatching {
            gson.fromJson(message.payload, RingPayload::class.java)
        }.getOrNull() ?: RingPayload()

        acquireTemporaryWakeLock((ringPayload.durationSec * 1000L).coerceAtMost(15_000L))
        val code = sirenManager.startRing(ringPayload.durationSec, ringPayload.volumePct)
        val success = (code == AckPayload.CODE_OK || code == AckPayload.CODE_VOLUME_RESTRICTED)
        val errorMsg = if (code == AckPayload.CODE_VOLUME_RESTRICTED) "系统音量限制，响铃无法拉满" else ""
        sendAck(message.msgId, message.fromDeviceId, code, errorMsg, success, message.payload)
    }

    private fun onCommandStopRing(message: InkMessage) {
        sirenManager.stopRing()
        sendAck(message.msgId, message.fromDeviceId, AckPayload.CODE_OK)
    }

    private fun onPushAlert(message: InkMessage) {
        val payload = runCatching {
            gson.fromJson(message.payload, AlertPushPayload::class.java)
        }.getOrNull() ?: AlertPushPayload()

        acquireTemporaryWakeLock(3000L)
        hostState.showAlert(HostAlert(payload.title, payload.content))

        if (payload.ring) {
            if (!idempotentController.checkAndRecord(message.msgId)) {
                sendAck(message.msgId, message.fromDeviceId, AckPayload.CODE_IDEMPOTENT_DROP, "重复告警响铃已忽略")
                return
            }
            val code = sirenManager.startRing(payload.durationSec, 100)
            val success = (code == AckPayload.CODE_OK || code == AckPayload.CODE_VOLUME_RESTRICTED)
            sendAck(message.msgId, message.fromDeviceId, code, "", success, message.payload)
        } else {
            sendAck(message.msgId, message.fromDeviceId, AckPayload.CODE_OK, "", true, message.payload)
        }

        if (payload.needNotification) {
            showAlertNotification(payload)
        }
    }

    private fun showAlertNotification(payload: AlertPushPayload) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionUtil.hasPermissions(this, android.Manifest.permission.POST_NOTIFICATIONS)
        ) {
            return
        }
        val intent = Intent(this, HostActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, ALERT_NOTIFY_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle(payload.title)
            .setContentText(payload.content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.content))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private fun sendAck(
        msgId: String,
        targetId: String?,
        code: Int,
        errorMsg: String = "",
        success: Boolean = (code == AckPayload.CODE_OK),
        snapshot: String? = null
    ) {
        val ack = AckPayload(
            ackMsgId = msgId,
            success = success,
            code = code,
            errorMsg = errorMsg,
            msgSnapshot = snapshot
        )
        val ackJson = gson.toJson(ack)
        transportManager.sendMessage(
            InkMessage.text(MessageType.CMD_ACK, ackJson, from = deviceId, target = targetId)
        )
    }

    private fun startPingHeartbeatLoop() {
        pingJob?.cancel()
        pingJob = serviceScope.launch {
            while (isActive) {
                delay(30_000L) // 每 30 秒发送 PING(10)
                // 复用同一 30s 节拍做饥饿越限检查：不为音频再开一个常驻定时器
                // （android-lead：属性/告警判定只在既有 tick 与指令到达时做，严禁新增循环）
                runCatching { maybeReportHungerAlert() }
                    .onFailure { hostState.appendLog("饥饿提醒检查异常：${it.message}") }
                val target = transportManager.defaultTargetDeviceId
                if (!target.isNullOrBlank()) {
                    transportManager.sendMessage(
                        InkMessage(
                            type = MessageType.PING.code,
                            fromDeviceId = deviceId,
                            targetDeviceId = target,
                            payload = """{"deviceId":"$deviceId","timestamp":${System.currentTimeMillis()}}"""
                        )
                    )
                }
                // 45s 超时判定
                if (System.currentTimeMillis() - lastPongTs > 45_000L && hostState.connected) {
                    hostState.setConnected(false)
                    hostState.appendLog("心跳超时 (45s)，判定主控端离线")
                }
            }
        }
    }

    private fun onGpsLocation(report: GpsReport) {
        val fenceEvents = geofenceManager.onLocation(report.lat, report.lng)
        for (fenceEvent in fenceEvents) {
            when (fenceEvent) {
                is FenceEvent.Exit -> {
                    val fenceName = fenceEvent.fence.name
                    hostState.appendLog("⚠️ 触发电子围栏离开警报${fenceName?.let { "[$it]" } ?: ""}！")
                    _petEventFlow.tryEmit(PetUiEvent.AlertTriggered)
                    val alertPayload = com.inklink.common.protocol.payload.PetAlertEventPayload(
                        alertType = "GEOFENCE_EXIT",
                        description = if (fenceName != null) "受控端越出安全围栏[$fenceName]" else "受控端越出安全围栏"
                    )
                    transportManager.sendMessage(
                        InkMessage(
                            type = MessageType.PET_ALERT_EVENT.code,
                            fromDeviceId = deviceId,
                            targetDeviceId = transportManager.defaultTargetDeviceId,
                            payload = gson.toJson(alertPayload)
                        )
                    )
                }
                is FenceEvent.Enter -> Unit
            }
        }
        treasureHunter.onLocationUpdate(report)
        if (reportThrottler.shouldReportLocation(report.lat, report.lng)) {
            broadcastGpsReport(report)
            // 离线补传数据源：与上报同节流点落盘（静止不写、漂移点丢弃）
            trackLogger.append(report)
        }
        for (event in fenceEvents) {
            acquireTemporaryWakeLock(2000L)
            when (event) {
                is FenceEvent.Enter -> {
                    val cfg = gson.toJson(GeofenceConfig.from(event.fence))
                    transportManager.sendMessage(InkMessage.text(MessageType.ALERT_ENTER, cfg, from = deviceId))
                    hostState.appendLog("进入围栏区域${event.fence.name?.let { "[$it]" } ?: ""}")
                }
                is FenceEvent.Exit -> {
                    val cfg = gson.toJson(GeofenceConfig.from(event.fence))
                    transportManager.sendMessage(InkMessage.text(MessageType.ALERT_EXIT, cfg, from = deviceId))
                    hostState.appendLog("离开围栏区域${event.fence.name?.let { "[$it]" } ?: ""}")
                }
            }
        }
    }

    private fun onRequestGps() {
        acquireTemporaryWakeLock(3000L)
        if (!::gpsManager.isInitialized) {
            gpsManager = GpsManager(this) { report ->
                onGpsLocation(report)
            }
        }
        gpsManager.requestSingleUpdate { report ->
            val payload = gson.toJson(report)
            transportManager.sendMessage(InkMessage.text(MessageType.GPS_REPORT, payload, from = deviceId))
        }
    }

    /** 主控端拉取历史轨迹：后台线程处理，逐块回传（断点续传见 HistoryServer）。 */
    private fun onHistoryRequest(message: InkMessage) {
        val payload = runCatching {
            gson.fromJson(message.payload, com.inklink.common.protocol.payload.HistoryRequestPayload::class.java)
        }.getOrNull() ?: return
        historyExecutor.execute {
            runCatching {
                val sent = historyServer.handleRequest(payload, message.fromDeviceId)
                hostState.appendLog("历史补传: reqId=${payload.reqId.take(8)} 补发 $sent 块")
            }.onFailure { hostState.appendLog("历史补传失败: ${it.message}") }
        }
    }

    private fun applyGeofence(payload: String?) {
        val cfg = runCatching { gson.fromJson(payload, GeofenceConfig::class.java) }.getOrNull() ?: return
        val fences = cfg.toGeoFences()
        geofenceManager.updateFences(fences)
        // 落盘：服务/进程重启后恢复（围栏生命周期对齐设备而非进程）
        payload?.let { geofenceStore.save(it) }
        hostState.appendLog("更新围栏 ${fences.size} 个: lat=${cfg.lat}, lng=${cfg.lng}, r=${cfg.radius}m")
    }

    private fun onAudioStart() {
        if (inCall) {
            transportManager.sendMessage(InkMessage.text(MessageType.AUDIO_STOP, REASON_BUSY, from = deviceId))
            return
        }
        if (!PermissionUtil.hasPermissions(this, PermissionUtil.RECORD_AUDIO)) {
            transportManager.sendMessage(InkMessage.text(MessageType.AUDIO_STOP, REASON_NO_PERMISSION, from = deviceId))
            return
        }
        inCall = true
        hostState.setInCall(true)
        transportManager.sendMessage(InkMessage.control(MessageType.AUDIO_START, from = deviceId))
        audioManager.startRecord { frame -> transportManager.sendAudio(frame.toFrame()) }
        hostState.appendLog("语音通话开始")
    }

    private fun onAudioStop() {
        inCall = false
        hostState.setInCall(false)
        audioManager.stopRecord()
        hostState.appendLog("语音通话结束")
    }

    private fun updateBatteryState(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100
        hostState.updateBattery(pct, isCharging)
    }

    private fun broadcastDeviceStatus() {
        val isOpt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(packageName) != true
        } else false

        val status = DeviceStatusPayload(
            batteryPct = hostState.batteryPct,
            isCharging = hostState.isCharging,
            batteryOptimized = isOpt,
            locationPermission = PermissionUtil.hasPermissions(this, PermissionUtil.LOCATION),
            audioPermission = PermissionUtil.hasPermissions(this, PermissionUtil.RECORD_AUDIO),
            isRinging = hostState.isRinging,
            // UNKNOWN 探测期传 null：把"还没测出来"报成"不支持"会让主控端开机瞬间误显提示
            ttsAvailable = when (com.inklink.host.audio.PetTtsGate.get(this).probe) {
                com.inklink.host.audio.PetTtsGate.Probe.UNKNOWN -> null
                com.inklink.host.audio.PetTtsGate.Probe.READY -> true
                com.inklink.host.audio.PetTtsGate.Probe.UNAVAILABLE -> false
            }
        )
        val payload = gson.toJson(status)
        transportManager.sendMessage(
            InkMessage.text(MessageType.DEVICE_STATUS_REPORT, payload, from = deviceId)
        )
    }

    private fun acquireTemporaryWakeLock(timeoutMs: Long) {
        runCatching {
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "inklink:temp_lock")?.apply {
                setReferenceCounted(false)
                acquire(timeoutMs.coerceAtMost(3000L))
            }
        }
    }

    private fun setupAlarmPing() {
        val intent = Intent(this, AlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmPendingIntent = pi
        alarmManager?.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 60_000L,
            60_000L,
            pi
        )
    }

    private fun cancelAlarmPing() {
        val pi = alarmPendingIntent
        if (pi != null) {
            alarmManager?.cancel(pi)
        }
    }

    private fun startAsForeground() {
        createChannel()
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, HostActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 14 起按已授予的运行时权限动态选择前台服务类型：
            // 麦克风/定位类型在对应权限未授予时调用 startForeground 会直接抛 SecurityException 闪退
            var type = 0
            if (PermissionUtil.hasPermissions(this, android.Manifest.permission.RECORD_AUDIO)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (PermissionUtil.hasPermissions(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ||
                PermissionUtil.hasPermissions(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            ) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (type == 0) {
                // 权限全部未授予时的兜底类型（Manifest 已声明 dataSync，无需运行时授权）
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            alertChannel.description = getString(R.string.alert_channel_desc)
            nm.createNotificationChannel(alertChannel)
        }
    }

    private fun onPetInteractCommand(message: InkMessage) {
        if (!idempotentController.checkAndRecord(message.msgId)) {
            sendAck(message.msgId, message.fromDeviceId, AckPayload.CODE_IDEMPOTENT_DROP, "重复互动指令已忽略")
            return
        }
        val payload = runCatching {
            gson.fromJson(message.payload, com.inklink.common.protocol.payload.PetInteractCmdPayload::class.java)
        }.getOrNull() ?: return
        handleInteractCore(
            action = payload.action,
            rawCount = payload.count,
            foodType = payload.foodType,
            replyTo = message.fromDeviceId
        )
    }

    /**
     * 31 / 23 共用的**唯一**宠物结算路径。
     *
     * 为什么必须共用：CMD_PET_EVENT(23) 的 `event_feed` 与 PET_INTERACT_CMD(31) 的 `FEED` 是同一件事，
     * 各写一套数值就会各扣一次饥饿；而幂等只按 msgId 去重（两条消息的 msgId 天然不同），根本挡不住双扣。
     *
     * [sleepTarget] 仅 23 使用：`event_sleep`/`event_wakeup` 是**目标态**语义，已达目标就不再 toggle，
     * 否则「唤醒」连点两次会把宠物又睡回去。31 传 null 保持原 toggle 行为不变。
     */
    private fun handleInteractCore(
        action: String,
        rawCount: Int,
        foodType: String,
        replyTo: String?,
        sleepTarget: Boolean? = null
    ) {
        val petManager = com.inklink.host.state.PetStateManager(this)
        val count = rawCount.coerceIn(1, 5)
        var deltaH = 0
        var deltaHappy = 0
        var success = true
        var note: String? = null
        var trigger = "FEED"
        when (action) {
            "FEED" -> {
                // foodType 复用为道具 itemId（V1.1 消耗化）；旧端默认 "SNACK" 映射普通食物
                val itemId = when (foodType) {
                    "food_premium" -> "food_premium"
                    else -> "food_normal"
                }
                val (dh, dpy, deny) = petManager.feed(count, itemId)
                deltaH = dh; deltaHappy = dpy; success = deny == null; note = deny
            }
            "PLAY" -> {
                val (dpy, _, deny) = petManager.playWith()
                deltaHappy = dpy; success = deny == null; note = deny
                trigger = "EXCITED"
            }
            "CLEAN" -> {
                val (dc, deny) = petManager.cleanPet()
                success = deny == null; note = deny
                trigger = "TOUCH"
            }
            "SLEEP" -> {
                val current = runCatching { petManager.getActivePet()?.isSleeping == true }.getOrDefault(false)
                if (sleepTarget != null && sleepTarget == current) {
                    // 已达目标态：幂等回执，不翻转状态机
                    note = if (sleepTarget) "已在睡眠中" else "本就醒着"
                    trigger = "IDLE"
                } else {
                    val (sleeping, deny) = petManager.toggleSleep()
                    success = deny == null
                    note = if (deny != null) deny else if (sleeping) "已哄睡" else "已唤醒"
                    trigger = "IDLE"
                }
            }
            "LEARN" -> {
                val (_, deny) = petManager.learn()
                success = deny == null; note = deny
                trigger = "TOUCH"
            }
            "HEAL" -> {
                val (ok, msg) = petManager.heal()
                success = ok; note = msg
                trigger = "EXCITED"
            }
            else -> {
                val (dpy, deny) = petManager.touchPet()
                deltaHappy = dpy; success = deny == null; note = deny
                trigger = "TOUCH"
            }
        }

        if (action == "FEED") {
            _petEventFlow.tryEmit(PetUiEvent.FeedRemote(count))
        } else {
            _petEventFlow.tryEmit(PetUiEvent.RemoteInteractDone(action, trigger, success, note))
        }

        val ackPayload = com.inklink.common.protocol.payload.PetInteractAckPayload(
            success = success,
            deltaHunger = deltaH,
            deltaHappiness = deltaHappy,
            petSnapshot = petManager.getActivePet(),
            note = note
        )
        transportManager.sendMessage(
            InkMessage(
                type = MessageType.PET_INTERACT_ACK.code,
                fromDeviceId = deviceId,
                targetDeviceId = replyTo,
                payload = gson.toJson(ackPayload)
            )
        )
    }

    /**
     * CMD_PLAY_SOUND(22)：只出声，不动任何宠物数值（文档 §一「预制音效为必选主干」）。
     * 未知 soundId 一律拒绝且不兜底播放；音效播放失败仍回 CODE_OK——声音坏了不算业务失败。
     */
    private fun onPlaySoundCommand(message: InkMessage) {
        if (!idempotentController.checkAndRecord(message.msgId)) {
            sendAck(message.msgId, message.fromDeviceId, AckPayload.CODE_IDEMPOTENT_DROP, "重复提示音指令已忽略")
            return
        }
        val payload = runCatching {
            gson.fromJson(message.payload, com.inklink.common.protocol.payload.PlaySoundPayload::class.java)
        }.getOrNull()
        val soundId = payload?.soundId.orEmpty()
        if (!com.inklink.common.protocol.payload.SoundProtocol.isKnownSound(soundId)) {
            hostState.appendLog("拒绝非法提示音指令：soundId=$soundId")
            sendAck(
                message.msgId, message.fromDeviceId, AckPayload.CODE_EXECUTION_ERROR,
                "未知 soundId=$soundId", success = false
            )
            return
        }
        val fb = audioFeedback
        if (fb == null) {
            sendAck(message.msgId, message.fromDeviceId, AckPayload.CODE_OK, "音频模块未就绪，仅回执")
            return
        }
        fb.feedback(soundId, payload?.ttsText, payload?.enableTts == true) { rep ->
            val line = "提示音 $soundId → ${rep.summary()}"
            hostState.appendLog(line)
            sendAck(message.msgId, message.fromDeviceId, AckPayload.CODE_OK, line)
        }
    }

    /**
     * CMD_PET_EVENT(23)：先结算状态（走 31 同一核心），再播音效 + 可选朗读。
     * 音频侧任何失败都不回滚状态、不抛异常（文档 §八 铁律 3）。
     */
    private fun onPetEventCommand(message: InkMessage) {
        if (!idempotentController.checkAndRecord(message.msgId)) {
            sendAck(message.msgId, message.fromDeviceId, AckPayload.CODE_IDEMPOTENT_DROP, "重复事件指令已忽略")
            return
        }
        val payload = runCatching {
            gson.fromJson(message.payload, com.inklink.common.protocol.payload.PetEventPayload::class.java)
        }.getOrNull()
        val eventId = payload?.eventId.orEmpty()
        val binding = com.inklink.common.protocol.payload.SoundProtocol.bindingFor(eventId)
        if (binding == null) {
            hostState.appendLog("拒绝未知宠物事件：eventId=$eventId")
            sendAck(
                message.msgId, message.fromDeviceId, AckPayload.CODE_EXECUTION_ERROR,
                "未知 eventId=$eventId", success = false
            )
            return
        }
        if (binding.hostReported) {
            // 该 ID 是受控端上报型；主控端回声发回来会形成事件环，直接丢弃
            sendAck(message.msgId, message.fromDeviceId, AckPayload.CODE_OK, "受控端上报型事件，忽略回声")
            return
        }
        val semanticKey = "$eventId@${payload?.triggerTs ?: 0L}"
        if (!eventSemanticGuard.checkAndRecord(semanticKey)) {
            sendAck(message.msgId, message.fromDeviceId, AckPayload.CODE_IDEMPOTENT_DROP, "重复事件已忽略(语义)")
            return
        }
        val act = binding.action
        if (act != null) {
            handleInteractCore(
                action = act,
                rawCount = binding.count,
                foodType = "SNACK",
                replyTo = message.fromDeviceId,
                sleepTarget = binding.sleeping
            )
        } else {
            sendAck(message.msgId, message.fromDeviceId, AckPayload.CODE_OK, "该事件不改变数值")
        }
        audioFeedback?.feedback(binding.soundId, payload?.ttsText, payload?.enableTts == true) { rep ->
            hostState.appendLog("事件 $eventId/${binding.soundId} → ${rep.summary()}")
        }
    }

    /**
     * 受控端主动饥饿提醒（文档 §六）：hunger<25 且存活清醒 → 本地播 pet_hungry + 上报 23。
     *
     * 必须带冷却：低饥饿是一个会持续为真的区间，不加冷却就是每 30s 一次响铃轰炸；
     * 阈值与 [com.inklink.host.pet.PetAiEngine] 的 RUB_BELLY 抱怨保持一致（25），
     * 避免「本地开始喊饿、家长端却没收到」的两套判据。
     */
    private fun maybeReportHungerAlert() {
        val pet = runCatching { com.inklink.host.state.PetStateManager(this).getActivePet() }.getOrNull()
            ?: return
        if (!pet.isAlive || pet.isSleeping || pet.hunger >= 25) return
        if (!hungerAlertThrottle.tryAcquire()) return
        val gate = com.inklink.host.audio.PetTtsGate.get(this)
        val text = "我饿了快来喂我"
        // enableTts 反映受控端自身可用性：主控端无音频资产，收到的 ttsText 只用于弹窗文案
        val report = com.inklink.common.protocol.payload.PetEventPayload(
            eventId = "event_hungry_alert",
            ttsText = text,
            enableTts = gate.available
        )
        val target = transportManager.defaultTargetDeviceId
        if (target.isNullOrBlank()) {
            // 无配对主控端：本地提醒照发，上报留待下次连接（不进 OfflineEventQueue——
            // 那是照料事件流水，延后重放一条过期"我饿了"只会变成噪音）
            hostState.appendLog("饥饿提醒未上报（主控端未配对），仅本地提醒")
        } else {
            transportManager.sendMessage(
                InkMessage(
                    type = MessageType.CMD_PET_EVENT.code,
                    fromDeviceId = deviceId,
                    targetDeviceId = target,
                    payload = gson.toJson(report)
                )
            )
        }
        audioFeedback?.feedback("pet_hungry", text, true) { rep ->
            hostState.appendLog("饥饿主动提醒 → ${rep.summary()}")
        }
    }

    private fun onPetRemoteGift(message: InkMessage) {
        if (!idempotentController.checkAndRecord(message.msgId)) {
            return
        }
        // V1.1 裁决#7 / 任务10.4：仅主控端可赠礼。Ably 好友频道白名单已排除 44，
        // 这里对局域网/公网进站再做一次防线：好友设备送来的 44 一律丢弃。
        val fromFriend = (application as InkHostApplication)
            .friendRepository.findByDeviceId(message.fromDeviceId) != null
        if (fromFriend) return
        val payload = runCatching {
            gson.fromJson(message.payload, com.inklink.common.protocol.payload.PetRemoteGiftPayload::class.java)
        }.getOrNull()
        if (payload == null) return
        val petManager = com.inklink.host.state.PetStateManager(this)
        val count = payload.count.coerceIn(1, 3)
        val itemId = payload.itemId
        var success = true
        var note: String? = null
        // 一键赠送全部（2026-08-31）：itemId=gift_all / giftType=all，聚合入账避免 14 连弹窗
        if (itemId == "gift_all" || payload.giftType == "all") {
            note = petManager.applyGiftAll()
            _petEventFlow.tryEmit(PetUiEvent.GiftReceived(itemId = itemId, count = 1, giftType = "all", note = note))
            val allAck = com.inklink.common.protocol.payload.PetGiftAckPayload(
                itemId = itemId,
                giftType = "all",
                success = true,
                note = note,
                petSnapshot = petManager.getActivePet()
            )
            transportManager.sendMessage(
                InkMessage(
                    type = MessageType.PET_INTERACT_ACK.code,
                    fromDeviceId = deviceId,
                    targetDeviceId = message.fromDeviceId,
                    payload = gson.toJson(allAck)
                )
            )
            return
        }
        // V1.1 三类礼物统一分发（44 零新增码位）；giftType 缺省时按 itemId 前缀推断，旧端兼容
        val type = when {
            payload.giftType.isNotBlank() && payload.giftType != "item" -> payload.giftType
            itemId.startsWith("buff_") -> "buff"
            itemId.startsWith("deco_") -> "deco"
            itemId.startsWith("coin_") -> "coin"
            else -> payload.giftType
        }
        when {
            type == "buff" -> {
                val (effect, deny) = petManager.applyBuff(itemId, count)
                success = deny == null
                note = deny ?: effect
            }
            type == "deco" -> {
                petManager.unlockDecoration(itemId)
                note = "解锁了新装饰"
            }
            itemId.startsWith("coin_") -> {
                // 任务激励闭环：coin_<额度> 奖励类目直接入账金币
                val perUnit = itemId.removePrefix("coin_").toIntOrNull() ?: 0
                petManager.petBag.coin += perUnit * count
                petManager.persist()
                note = "金币 +${perUnit * count}"
            }
            com.inklink.host.state.PetCatalog.ITEMS.containsKey(itemId) -> {
                petManager.addItem(itemId, count)
                note = "收到 ${com.inklink.host.state.PetCatalog.ITEMS[itemId]?.name ?: itemId}"
            }
            else -> {
                // 未知类目按零花钱兼容处理（如旧版 SNACK_GIFT_BOX），不再直接改属性
                petManager.petBag.coin += 20 * count
                petManager.persist()
                note = "收到神秘礼包，兑换了 ${20 * count} 金币"
            }
        }
        _petEventFlow.tryEmit(PetUiEvent.GiftReceived(itemId, count, type, note))
        // 赠礼 ACK（V1.1：重要指令补回执，主控端幂等展示到账）
        val ackPayload = com.inklink.common.protocol.payload.PetGiftAckPayload(
            itemId = itemId,
            giftType = type,
            success = success,
            note = note,
            petSnapshot = petManager.getActivePet()
        )
        transportManager.sendMessage(
            InkMessage(
                type = MessageType.PET_INTERACT_ACK.code,
                fromDeviceId = deviceId,
                targetDeviceId = message.fromDeviceId,
                payload = gson.toJson(ackPayload)
            )
        )
    }

    private fun onPetGameInvite(message: InkMessage) {
        // 33 邀请幂等：防网络抖动重复弹窗
        if (!idempotentController.checkAndRecord(message.msgId)) return
        val payload = runCatching {
            gson.fromJson(message.payload, com.inklink.common.protocol.payload.PetGameInvitePayload::class.java)
        }.getOrNull()
        if (payload != null) {
            _petEventFlow.tryEmit(PetUiEvent.GameInviteReceived(payload.inviteId, payload.gameType, message.fromDeviceId ?: ""))
        }
    }

    private fun onPetGameAction(message: InkMessage) {
        val payload = runCatching {
            gson.fromJson(message.payload, com.inklink.common.protocol.payload.PetGameActionPayload::class.java)
        }.getOrNull() ?: return
        // 发起方受控端：消费出招记录并本地判定胜负（受控端互玩）
        val pending = com.inklink.host.friend.GameInviteSession.consume(payload.inviteId)
        if (pending != null) {
            val verdict = when {
                pending.myChoice == payload.actionData -> "DRAW"
                (pending.myChoice == "ROCK" && payload.actionData == "SCISSORS") ||
                    (pending.myChoice == "SCISSORS" && payload.actionData == "PAPER") ||
                    (pending.myChoice == "PAPER" && payload.actionData == "ROCK") -> "WIN"
                else -> "LOSE"
            }
            android.widget.Toast.makeText(
                this,
                when (verdict) {
                    "WIN" -> "🎉 你赢了！"
                    "LOSE" -> "😆 对方赢了！"
                    else -> "🤝 平局！"
                },
                android.widget.Toast.LENGTH_LONG
            ).show()
            _petEventFlow.tryEmit(PetUiEvent.GameResult(verdict, pending.myChoice, payload.actionData))
            return
        }
        _petEventFlow.tryEmit(PetUiEvent.GameActionReceived(payload.inviteId, payload.actionData))
    }

    /**
     * 串门/逗弄（43 JSON 子类型）：被访端活跃宠物心情 +3，广播「XX 来串门啦」气泡事件。
     * TEASE（好友远程逗弄）扣一点心情换欢笑，其余到访统一加心。
     */
    private fun onPetBagInteract(message: InkMessage) {
        if (!idempotentController.checkAndRecord(message.msgId)) return
        val app = application as InkHostApplication
        val payload = runCatching {
            gson.fromJson(message.payload, com.inklink.common.protocol.payload.PetBagInteractPayload::class.java)
        }.getOrNull()
        val visitorName = payload?.visitorName?.takeIf { it.isNotBlank() }
            ?: app.friendRepository.findByDeviceId(message.fromDeviceId)?.nickname
            ?: "小伙伴"
        val petManager = com.inklink.host.state.PetStateManager(this)
        if (payload?.subType == "TEASE") petManager.annoyPet() else petManager.touchPet()
        petManager.logEvent("VISIT", "$visitorName 来串门了")
        _petEventFlow.tryEmit(PetUiEvent.FriendVisit(visitorName, payload?.visitorPetType ?: ""))
    }

    private fun onPetStateSyncRequest(message: InkMessage) {
        val petManager = com.inklink.host.state.PetStateManager(this)
        val pet = petManager.recalculateState()
        transportManager.sendMessage(
            InkMessage(
                type = MessageType.PET_STATE_SYNC.code,
                fromDeviceId = deviceId,
                targetDeviceId = message.fromDeviceId,
                payload = gson.toJson(pet)
            )
        )
    }

    private fun broadcastGpsReport(report: GpsReport) {
        val payload = gson.toJson(report)
        transportManager.sendMessage(
            InkMessage.text(MessageType.GPS_REPORT, payload, from = deviceId)
        )
    }

    private fun onPetBagSyncRequest(message: InkMessage) {
        val petManager = com.inklink.host.state.PetStateManager(this)
        petManager.recalculateState()
        transportManager.sendMessage(
            InkMessage(
                type = MessageType.PET_BAG_SYNC.code,
                fromDeviceId = deviceId,
                targetDeviceId = message.fromDeviceId,
                payload = gson.toJson(petManager.petBag)
            )
        )
    }

    private fun onRemoteTaskSend(message: InkMessage) {
        if (!idempotentController.checkAndRecord(message.msgId)) {
            return
        }
        val payload = runCatching {
            gson.fromJson(message.payload, com.inklink.common.protocol.payload.RemoteTaskPayload::class.java)
        }.getOrNull()
        if (payload != null) {
            val taskManager = com.inklink.host.task.TaskQueueManager(this)
            val added = taskManager.addTask(payload)
            if (added) {
                _petEventFlow.tryEmit(PetUiEvent.NewRemoteTask(payload))
                showRemoteTaskNotification(payload)
            }
            val ack = com.inklink.common.protocol.payload.RemoteTaskAckPayload(
                taskId = payload.taskId,
                status = "RECEIVED"
            )
            transportManager.sendMessage(
                InkMessage(
                    type = MessageType.REMOTE_TASK_ACK.code,
                    fromDeviceId = deviceId,
                    targetDeviceId = message.fromDeviceId,
                    payload = gson.toJson(ack)
                )
            )
        }
    }

    private fun flushOfflineEvents() {
        val events = offlineEventQueue.drain()
        // 重连后按协议做 PET_BAG_SYNC(40) 背包全量同步（覆盖 Ably 72h 历史丢失场景，
        // 短离线全量同步是超集行为，主控端 PetDetailActivity 直接消费该消息）
        val petManager = com.inklink.host.state.PetStateManager(this)
        val bag = petManager.petBag
        transportManager.sendMessage(
            InkMessage(
                type = MessageType.PET_BAG_SYNC.code,
                fromDeviceId = deviceId,
                targetDeviceId = transportManager.defaultTargetDeviceId,
                payload = gson.toJson(bag)
            )
        )
        hostState.appendLog("重连成功：背包全量同步 (${bag.petList.size} 只)，离线事件 ${events.size} 条")
    }

    private fun showRemoteTaskNotification(payload: com.inklink.common.protocol.payload.RemoteTaskPayload) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val playIntent = Intent(this, com.inklink.host.receiver.TaskPlayActionReceiver::class.java).apply {
            putExtra("task_id", payload.taskId)
            putExtra("task_content", payload.content)
        }
        val playPendingIntent = PendingIntent.getBroadcast(
            this,
            payload.taskId.hashCode(),
            playIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle("📢 收到新的家长任务")
            .setContentText(payload.content)
            .addAction(android.R.drawable.ic_media_play, "▶ 播放语音", playPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(payload.taskId.hashCode(), notification)
    }

    private fun onResetHostPinCommand(message: InkMessage) {
        val payload = runCatching {
            gson.fromJson(message.payload, com.inklink.common.protocol.payload.ResetHostPinPayload::class.java)
        }.getOrNull()
        if (payload != null) {
            val app = application as InkHostApplication
            val pinManager = com.inklink.host.security.PinSecurityManager(this)
            val success = pinManager.resetPinRemote(
                newPinHash = payload.newPinHash,
                timestamp = payload.timestamp,
                signature = payload.authSignature,
                pairingKey = app.pairingKey
            )
            sendAck(message.msgId, message.fromDeviceId, if (success) AckPayload.CODE_OK else AckPayload.CODE_PERMISSION_DENIED)
        }
    }

    companion object {
        private const val CHANNEL_ID = "inklink_foreground"
        private const val ALERT_CHANNEL_ID = "inklink_alert"
        private const val ALERT_NOTIFY_REQUEST_CODE = 2001
        /** 饥饿提醒冷却：数值在阈值下会持续为真，无冷却=每 30s 轰炸一次 */
        private const val HUNGER_ALERT_GAP_MS = 600_000L
        private const val NOTIFICATION_ID = 1001
        private const val REASON_NO_PERMISSION = "NO_PERMISSION"
        private const val REASON_BUSY = "BUSY"

        private val _petEventFlow = kotlinx.coroutines.flow.MutableSharedFlow<PetUiEvent>(extraBufferCapacity = 16)
        val petEventFlow: kotlinx.coroutines.flow.SharedFlow<PetUiEvent> = _petEventFlow
    }

    sealed interface PetUiEvent {
        /** 家长远程投喂：Service 已完成状态结算，UI 仅播放动画（不得再次结算） */
        data class FeedRemote(val count: Int) : PetUiEvent

        /** 家长远程日常照料（玩耍/清洁/学习/睡眠/治疗）：Service 已结算，UI 播放反馈 */
        data class RemoteInteractDone(
            val action: String,
            val trigger: String,
            val success: Boolean,
            val note: String?
        ) : PetUiEvent

        data class NewRemoteTask(val task: com.inklink.common.protocol.payload.RemoteTaskPayload) : PetUiEvent
        /** 主控端赠礼到账：giftType ∈ item/deco/buff/coin */
        data class GiftReceived(
            val itemId: String,
            val count: Int,
            val giftType: String = "item",
            val note: String? = null
        ) : PetUiEvent
        data class GameInviteReceived(val inviteId: String, val gameType: String, val fromDeviceId: String) : PetUiEvent
        data class GameActionReceived(val inviteId: String, val actionData: String) : PetUiEvent

        /** 受控端互玩对局结算：verdict 为 WIN/LOSE/DRAW */
        data class GameResult(val verdict: String, val myChoice: String, val opponentAction: String) : PetUiEvent

        /** 好友跨设备串门到访（visitorPetType 非空时触发碰头动画） */
        data class FriendVisit(val visitorName: String, val visitorPetType: String = "") : PetUiEvent
        data class TreasureReward(val coinGain: Int, val expGain: Int) : PetUiEvent
        object AlertTriggered : PetUiEvent
        object AlertDismissed : PetUiEvent
    }
}
