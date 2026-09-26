package com.inklink.controller.state

import android.os.Handler
import android.os.Looper
import com.inklink.common.protocol.payload.AckPayload
import com.inklink.common.protocol.payload.DeviceStatusPayload
import com.inklink.common.service.gps.GpsReport
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 告警信息（进入/离开围栏）。
 */
data class AlertInfo(
    val type: String,
    val lat: Double,
    val lng: Double,
    val radius: Double,
    val time: Long,
    val deviceId: String? = null
)

/**
 * 主控端设备在线三态。
 */
enum class DeviceOnlineState {
    ONLINE_NORMAL,     // 🟢 在线且健康
    ONLINE_RESTRICTED, // 🟡 在线但后台优化开启或权限受限（高风险掉线）
    OFFLINE            // 🔴 离线
}

/**
 * 主控端共享状态。
 */
class ControllerState {

    interface Listener {
        fun onStateChanged()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val ackListeners = CopyOnWriteArrayList<(AckPayload) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    var connected = false
        private set
    var latestGps: GpsReport? = null
        private set
    var latestAlert: AlertInfo? = null
        private set
    var inCall = false
        private set

    /** 各受控端最新位置：deviceId → 最新 GPS 上报。 */
    @Volatile
    var gpsByDevice: Map<String, GpsReport> = emptyMap()
        private set

    /** 各受控端最近活跃时间：deviceId → 最近收到消息的时间戳。 */
    @Volatile
    var lastSeenByDevice: Map<String, Long> = emptyMap()
        private set

    /** 各受控端硬件与运行状态：deviceId → DeviceStatusPayload */
    @Volatile
    var statusByDevice: Map<String, DeviceStatusPayload> = emptyMap()
        private set

    /** 各受控端响铃状态：deviceId → Boolean */
    @Volatile
    var isRingingByDevice: Map<String, Boolean> = emptyMap()
        private set

    /** 主控端自身位置（地图绿色标记，locType=0）。 */
    @Volatile
    var selfLocation: GpsReport? = null
        private set

    /** 各受控端历史轨迹：deviceId → 最近若干 GPS 点（时间升序）。 */
    @Volatile
    var trajectoryByDevice: Map<String, List<GpsReport>> = emptyMap()
        private set

    /** 各受控端最新学习简报（LEARN_PROGRESS/47）：deviceId → 一行文本。 */
    @Volatile
    var learnSummaryByDevice: Map<String, String> = emptyMap()
        private set

    /** 当前选中的目标设备。 */
    var selectedDeviceId: String? = null
        private set

    /** 学习简报落状态(主线程回调刷新卡片)。 */
    fun setLearnSummary(deviceId: String, summary: String) {
        learnSummaryByDevice = learnSummaryByDevice + (deviceId to summary)
        notifyChanged()
    }

    fun setConnected(value: Boolean) {
        connected = value
        notifyChanged()
    }

    fun setGps(deviceId: String, gps: GpsReport) {
        gpsByDevice = gpsByDevice + (deviceId to gps)
        lastSeenByDevice = lastSeenByDevice + (deviceId to System.currentTimeMillis())
        trajectoryByDevice = trajectoryByDevice +
                (deviceId to ((trajectoryByDevice[deviceId] ?: emptyList()) + gps).takeLast(MAX_TRAJECTORY_POINTS))
        latestGps = gps
        notifyChanged()
    }

    fun setDeviceStatus(deviceId: String, status: DeviceStatusPayload) {
        statusByDevice = statusByDevice + (deviceId to status)
        lastSeenByDevice = lastSeenByDevice + (deviceId to System.currentTimeMillis())
        notifyChanged()
    }

    fun setDeviceRinging(deviceId: String, ringing: Boolean) {
        isRingingByDevice = isRingingByDevice + (deviceId to ringing)
        notifyChanged()
    }

    fun setSelfLocation(gps: GpsReport) {
        selfLocation = gps
        notifyChanged()
    }

    fun markSeen(deviceId: String) {
        if (deviceId.isBlank()) return
        lastSeenByDevice = lastSeenByDevice + (deviceId to System.currentTimeMillis())
        notifyChanged()
    }

    fun dispatchAck(ack: AckPayload) {
        mainHandler.post {
            ackListeners.forEach { it.invoke(ack) }
        }
    }

    fun addAckListener(listener: (AckPayload) -> Unit) {
        ackListeners.add(listener)
    }

    fun removeAckListener(listener: (AckPayload) -> Unit) {
        ackListeners.remove(listener)
    }

    fun selectDevice(deviceId: String?) {
        selectedDeviceId = deviceId
        notifyChanged()
    }

    fun isOnline(deviceId: String): Boolean {
        val lastSeen = lastSeenByDevice[deviceId] ?: return false
        return System.currentTimeMillis() - lastSeen < ONLINE_THRESHOLD_MS
    }

    /**
     * 获取设备精细化三态。
     */
    fun getDeviceOnlineState(deviceId: String): DeviceOnlineState {
        if (!isOnline(deviceId)) return DeviceOnlineState.OFFLINE
        val status = statusByDevice[deviceId]
        if (status != null && (status.batteryOptimized || !status.locationPermission)) {
            return DeviceOnlineState.ONLINE_RESTRICTED
        }
        return DeviceOnlineState.ONLINE_NORMAL
    }

    fun setAlert(alert: AlertInfo) {
        latestAlert = alert
        notifyChanged()
    }

    fun setInCall(value: Boolean) {
        inCall = value
        notifyChanged()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        mainHandler.post {
            listeners.forEach { it.onStateChanged() }
        }
    }

    companion object {
        const val ONLINE_THRESHOLD_MS = 60_000L
        const val MAX_TRAJECTORY_POINTS = 500
    }
}
