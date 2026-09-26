package com.inklink.common.protocol.payload

/**
 * 主控端推送告警载荷（PUSH_ALERT, type=21）。
 *
 * [title] 告警标题，[content] 告警正文。
 * [ring] 为 true 时受控端同时触发强制响铃；[durationSec] 响铃时长（3~60 秒）。
 * [needNotification] 为 true 时受控端在系统通知栏展示高优先级告警通知。
 */
data class AlertPushPayload(
    val title: String = "紧急告警",
    val content: String = "",
    val ring: Boolean = false,
    val durationSec: Int = 15,
    val needNotification: Boolean = true
)
