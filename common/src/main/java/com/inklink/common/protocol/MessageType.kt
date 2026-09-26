package com.inklink.common.protocol

enum class MessageType(val code: Int) {
    TEXT(1),
    IMAGE(2),
    GPS_REPORT(3),
    CMD_CLEAR(4),
    GEOFENCE_CONFIG(5),
    ALERT_ENTER(6),
    ALERT_EXIT(7),
    AUDIO_DATA(8),
    AUDIO_START(9),
    AUDIO_STOP(10),
    REQUEST_GPS(11),
    CHAT_TEXT(12),
    CHAT_IMAGE(13),
    CHAT_AUDIO(14),
    CMD_ACK(15),
    CMD_RING(16),
    CMD_STOP_RING(17),
    DEVICE_STATUS_REPORT(18),
    REFRESH_SCREEN_DEEP(19),
    SCREEN_CACHE_RESTORE(20),
    PUSH_ALERT(21),

    // 互动音频系统（《音频系统 Final-Rev1》§一）
    // 22 = 通用提示音：soundId 播预制 wav，ttsText/enableTts 为可选附加播报
    // 23 = 宠物行为事件：eventId 必触发「状态变更 + 预制音效」，播报可选
    // ⚠️ 23 与 PET_INTERACT_CMD(31) 语义重叠：受控端两侧必须汇入同一条结算路径
    //    （InkForegroundService.handleInteractCore），禁止各写一套属性增减，
    //    否则同一次投喂会被两条链路各扣一次饥饿（幂等只按 msgId 去重，跨类型不去重）。
    CMD_PLAY_SOUND(22),
    CMD_PET_EVENT(23),

    // 网络与通用错误控制
    // 注意：码位 10/11/12 已被旧协议 AUDIO_STOP/REQUEST_GPS/CHAT_TEXT 占用，
    // 此处使用 50-52，避免 fromCode 冲突导致消息被静默吞掉
    PING(50),
    PONG(51),
    ERROR_RESPONSE(52),

    // 核心宠物系统协议 (30-36)
    PET_STATE_SYNC(30),
    PET_INTERACT_CMD(31),
    PET_INTERACT_ACK(32),
    PET_GAME_INVITE(33),
    PET_GAME_ACTION(34),
    PET_ALERT_EVENT(35),
    CMD_RESET_HOST_PIN(36),

    // 背包、商店与多宠物交互 (40-44)
    PET_BAG_SYNC(40),
    PET_SWITCH_ACTIVE(41),
    PET_SHOP_BUY(42),
    PET_BAG_INTERACT(43),
    PET_REMOTE_GIFT(44),

    // 远程语音任务系统 (45-46)
    REMOTE_TASK_SEND(45),
    REMOTE_TASK_ACK(46),

    // 学习系统 (47-49):见 docs/inklink-study-implementation-plan.md 附B
    LEARN_PROGRESS(47),
    HOMEWORK_ASSIGN(48),
    HOMEWORK_ACK(49),

    HEARTBEAT(99);

    companion object {
        private val BY_CODE = entries.associateBy { it.code }

        fun fromCode(code: Int): MessageType? = BY_CODE[code]

        fun requireCode(code: Int): MessageType =
            fromCode(code) ?: throw IllegalArgumentException("未知消息类型 code=$code")
    }
}
