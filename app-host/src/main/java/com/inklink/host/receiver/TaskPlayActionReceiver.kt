package com.inklink.host.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import com.inklink.common.protocol.InkMessage
import com.inklink.common.protocol.MessageType
import com.inklink.common.protocol.payload.RemoteTaskAckPayload
import com.inklink.host.InkHostApplication
import com.inklink.host.task.TaskQueueManager

/**
 * 通知栏 Action 按钮一键播放语音任务接收器。
 * 播放同时回执 REMOTE_TASK_ACK(46, PLAYED) 给主控端。
 */
class TaskPlayActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val taskId = intent?.getStringExtra("task_id") ?: return
        val content = intent.getStringExtra("task_content") ?: return
        val result = goAsync()

        // 双路径（TTS 回调 / 15s 兜底）都会 finish：重复调用依赖框架容忍，
        // 显式只 finish 一次更稳
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) result.finish()
        }

        // Room 写库与 ACK 放出主线程：onReceive 同步写库会把 10s 广播预算耗在 IO 上
        val app = context.applicationContext as? InkHostApplication
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            TaskQueueManager(context).markPlayed(taskId)

            // 回执 PLAYED 状态（清单：任务接收与点击播放双状态回执）
            if (app != null) runCatching {
                val ack = RemoteTaskAckPayload(taskId = taskId, status = "PLAYED")
                app.transportManager.sendMessage(
                    InkMessage(
                        type = MessageType.REMOTE_TASK_ACK.code,
                        fromDeviceId = app.deviceId,
                        targetDeviceId = app.transportManager.defaultTargetDeviceId,
                        payload = Gson().toJson(ack)
                    )
                )
            }

            // 走进程级门控：旧实现每次广播 new 一个 LocalTtsManager（一条新的 TTS Binder 连接），
            // 靠回调里 release 兜底；一旦引擎初始化失败走 onUnavailable，onDone 不一定被调用，
            // pendingResult 就悬着。单例 + 显式超时才是稳的。
            com.inklink.host.audio.PetTtsGate.get(context).speak(
                content,
                maxLen = com.inklink.common.protocol.payload.SoundProtocol.TASK_TTS_MAX_LEN
            ) {
                finishOnce()
            }
            // TTS 初始化兜底超时：若 15s 内引擎仍未就绪则直接结束，避免 pendingResult 泄漏
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                finishOnce()
            }, 15_000L)

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(taskId.hashCode())
        }
    }
}
