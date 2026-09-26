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

    private data class DeviceCareState(
        var lastAliveTs: Long = 0L,
        var offlineSinceTs: Long = 0L,
        var lastOfflineNotifyTs: Long = 0L,
        var sessionStartTs: Long = 0L,
        var lastRestNotifyTs: Long = 0L
    )

    private val states = ConcurrentHashMap<String, DeviceCareState>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Volatile
    private var started = false

    /** 受控端活跃信号（PING/HEARTBEAT 到达时调用）。 */
    fun onPeerAlive(deviceId: String) {
        if (deviceId.isBlank()) return
        states.getOrPut(deviceId) { DeviceCareState() }.lastAliveTs = System.currentTimeMillis()
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
        val now = System.currentTimeMillis()
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
