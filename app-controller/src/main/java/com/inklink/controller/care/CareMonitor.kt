package com.inklink.controller.care

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 看护提醒（阶段五，主控端本地逻辑，零新增协议）：
 * - 目标设备离线持续超 30 分钟 → 本地通知提醒；
 * - 目标设备连续在线超 2 小时 → 「该让孩子休息了」提醒。
 *
 * 活跃信号：受控端 PING(50) 与 HEARTBEAT 心跳；45s 无信号判定离线。
 * 同一事件周期只提醒一次（离线期间不重复；连续在线提醒后重置会话计时）。
 */
class CareMonitor(private val context: Context) {

    /**
     * 设备看护状态。字段由传输回调线程（onPeerAlive）写、评估协程读：
     * ConcurrentHashMap 只保证 map 级安全，字段本身必须 @Volatile，
     * 否则评估线程可能长期读到陈旧时间戳，出现"活着的设备被判离线"的误报。
     *
     * 全部时间戳为单调钟域（见 [MonoClock]）：墙钟回拨会让窗口差值恒负，
     * 离线判定与提醒冷却同时静默失效。
     */
    private data class DeviceCareState(
        @Volatile var lastAliveTs: Long = 0L,
        @Volatile var offlineSinceTs: Long = 0L,
        @Volatile var lastOfflineNotifyTs: Long = 0L,
        @Volatile var sessionStartTs: Long = 0L,
        @Volatile var lastRestNotifyTs: Long = 0L
    )

    private val states = ConcurrentHashMap<String, DeviceCareState>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val hasNotifyPermission = {
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    @Volatile
    private var started = false

    /** 受控端活跃信号（PING/HEARTBEAT 到达时调用）。 */
    fun onPeerAlive(deviceId: String) {
        if (deviceId.isBlank()) return
        states.getOrPut(deviceId) { DeviceCareState() }.lastAliveTs = com.inklink.common.utils.MonoClock.now()
    }

    fun start() {
        if (started) return
        started = true
        createChannel()
        scope.launch {
            while (isActive) {
                delay(60_000L)
                runCatching { evaluate() }
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    private fun evaluate() {
        val now = com.inklink.common.utils.MonoClock.now()
        states.forEach { (deviceId, st) ->
            val online = now - st.lastAliveTs <= OFFLINE_THRESHOLD_MS
            if (st.lastAliveTs == 0L) return@forEach
            if (online) {
                // 离线恢复：重置离线起点
                st.offlineSinceTs = 0
                if (st.sessionStartTs == 0L) st.sessionStartTs = now
                // 在线超 2h：提醒后重置会话，再过 2h 才会再次提醒
                if (now - st.sessionStartTs >= OVERTIME_THRESHOLD_MS &&
                    now - st.lastRestNotifyTs >= REST_NOTIFY_COOLDOWN_MS
                ) {
                    st.lastRestNotifyTs = now
                    st.sessionStartTs = now
                    notify(
                        deviceId,
                        "⏰ 该让孩子休息了",
                        "设备 ${deviceId.take(8)} 已连续在线超过 2 小时"
                    )
                }
            } else {
                st.sessionStartTs = 0
                if (st.offlineSinceTs == 0L) st.offlineSinceTs = now
                // 离线超 30min：只提醒一次，恢复在线后重置
                if (now - st.offlineSinceTs >= OFFLINE_NOTIFY_DELAY_MS &&
                    st.lastOfflineNotifyTs < st.offlineSinceTs
                ) {
                    st.lastOfflineNotifyTs = now
                    notify(
                        deviceId,
                        "📭 孩子长时间离线",
                        "设备 ${deviceId.take(8)} 已离线超过 30 分钟"
                    )
                }
            }
        }
    }

    private fun notify(deviceId: String, title: String, text: String) {
        // Android 13+ 未授予通知权限时 notify 静默无效：核心看护提醒（离线 30min/
        // 连续在线 2h）会整体消失且无任何提示，与 sendAlertNotification 口径对齐
        if (!hasNotifyPermission()) return
        val notification = NotificationCompat.Builder(context, CARE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(deviceId.hashCode(), notification) }
    }

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CARE_CHANNEL_ID,
                    "看护提醒",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    companion object {
        private const val CARE_CHANNEL_ID = "inklink_care"
        private const val OFFLINE_THRESHOLD_MS = 45_000L
        private const val OFFLINE_NOTIFY_DELAY_MS = 30 * 60_000L
        private const val OVERTIME_THRESHOLD_MS = 2 * 3_600_000L
        private const val REST_NOTIFY_COOLDOWN_MS = 30 * 60_000L
    }
}
