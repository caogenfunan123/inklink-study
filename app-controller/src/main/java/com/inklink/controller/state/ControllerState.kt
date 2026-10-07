package com.inklink.controller.state

import android.os.Handler
import android.os.Looper
import com.inklink.common.protocol.payload.AckPayload
import com.inklink.common.protocol.payload.DeviceStatusPayload
import com.inklink.common.service.gps.GpsReport
import com.inklink.common.utils.MonoClock
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
 * 历史轨迹拉取进度（HISTORY_REQUEST/CHUNK/ACK 53-55）。
 */
data class HistoryProgress(
    val deviceId: String,
    val reqId: String,
    val received: Int,
    val total: Int,
    val inserted: Int = 0,
    val done: Boolean = false,
    val failed: Boolean = false,
    val failedReason: String? = null
)

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

    /**
     * 多设备 Map 的读-改-写串行化。
     *
     * @Volatile 只保证引用替换可见，「p = p + (id to v)」这种读-改-写并非原子：
     * 两条并发消息回调（Ably 分发、模式切换新旧 transport 交叠、history io 线程）
     * 会基于陈旧基线互相覆盖，静默丢一次位置/状态更新。
     */
    private val stateLock = Any()

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

    /** 各受控端最近活跃时间：deviceId → 最近收到消息的单调钟读数。 */
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

    /** 历史轨迹拉取进度（单并发：同一时刻至多一个拉取任务）。 */
    @Volatile
    var historyProgress: HistoryProgress? = null
        private set

    fun setHistoryProgress(progress: HistoryProgress?) {
        historyProgress = progress
        notifyChanged()
    }

    /** 学习简报落状态(主线程回调刷新卡片)。 */
    fun setLearnSummary(deviceId: String, summary: String) {
        synchronized(stateLock) {
            learnSummaryByDevice = learnSummaryByDevice + (deviceId to summary)
        }
        notifyChanged()
    }

    fun setConnected(value: Boolean) {
        connected = value
        notifyChanged()
    }

    fun setGps(deviceId: String, gps: GpsReport) {
        synchronized(stateLock) {
            gpsByDevice = gpsByDevice + (deviceId to gps)
            lastSeenByDevice = lastSeenByDevice + (deviceId to MonoClock.now())
            trajectoryByDevice = trajectoryByDevice +
                    (deviceId to ((trajectoryByDevice[deviceId] ?: emptyList()) + gps).takeLast(MAX_TRAJECTORY_POINTS))
            latestGps = gps
        }
        notifyChanged()
    }

    fun setDeviceStatus(deviceId: String, status: DeviceStatusPayload) {
        synchronized(stateLock) {
            statusByDevice = statusByDevice + (deviceId to status)
            lastSeenByDevice = lastSeenByDevice + (deviceId to MonoClock.now())
        }
        notifyChanged()
    }

    fun setDeviceRinging(deviceId: String, ringing: Boolean) {
        synchronized(stateLock) {
            isRingingByDevice = isRingingByDevice + (deviceId to ringing)
        }
        notifyChanged()
    }

    fun setSelfLocation(gps: GpsReport) {
        selfLocation = gps
        notifyChanged()
    }

    fun markSeen(deviceId: String) {
        if (deviceId.isBlank()) return
        synchronized(stateLock) {
            lastSeenByDevice = lastSeenByDevice + (deviceId to MonoClock.now())
        }
        notifyChanged()
    }

    /**
     * 清除某设备的全部内存数据（用户删除设备时调用）。
     * 不调用的话：Map 只增不减，地图清理分支永远命中不了，被删设备的
     * Marker/轨迹/状态会残留在界面上，焦点逻辑还可能把它算作"最近上报"。
     */
    fun removeDeviceData(deviceId: String) {
        synchronized(stateLock) {
            gpsByDevice = gpsByDevice - deviceId
            lastSeenByDevice = lastSeenByDevice - deviceId
            statusByDevice = statusByDevice - deviceId
            isRingingByDevice = isRingingByDevice - deviceId
            trajectoryByDevice = trajectoryByDevice - deviceId
            learnSummaryByDevice = learnSummaryByDevice - deviceId
            if (selectedDeviceId == deviceId) selectedDeviceId = null
        }
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
        // 单调钟：墙钟被调快会让活着的设备显示离线、调慢让离线设备显示在线
        val lastSeen = lastSeenByDevice[deviceId] ?: return false
        return MonoClock.now() - lastSeen < ONLINE_THRESHOLD_MS
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
        /**
         * 在线超时。与 CareMonitor.OFFLINE_THRESHOLD_MS（45s）保持同一口径：
         * 以前 UI 60s / 看护 45s 两个值，设备恰在 45-60s 无信号区间时
         * Dashboard 显示在线而看护已计为离线，家长端口径互相矛盾。
         */
        const val ONLINE_THRESHOLD_MS = 45_000L
        const val MAX_TRAJECTORY_POINTS = 500
    }
}
