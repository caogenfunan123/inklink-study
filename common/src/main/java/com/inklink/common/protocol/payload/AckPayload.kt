package com.inklink.common.protocol.payload

/**
 * 通用指令应答确认（CMD_ACK, type=15）。
 */
data class AckPayload(
    val ackMsgId: String,
    val success: Boolean,
    val code: Int = CODE_OK,
    val errorMsg: String = "",
    val msgSnapshot: String? = null
) {
    companion object {
        const val CODE_OK = 0
        const val CODE_VOLUME_RESTRICTED = 101 // 系统音量受限
        const val CODE_PERMISSION_DENIED = 102  // 缺少必要权限
        const val CODE_EXECUTION_ERROR = 103    // 指令执行异常
        const val CODE_LOCATION_DISABLED = 105  // 定位服务被关闭
        const val CODE_BATTERY_RESTRICTED = 106 // 电池优化未关闭，后台受限
        const val CODE_IDEMPOTENT_DROP = 107    // 重复消息，幂等丢弃
    }
}
