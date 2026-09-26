package com.inklink.common.transport

import com.inklink.common.protocol.InkMessage

/**
 * 传输层监听回调。局域网与公网模式共用同一套回调，上层业务无感知底层。
 */
interface TransportListener {
    /** 收到文本消息（含 JSON 化的 InkMessage）。 */
    fun onTextMessage(message: InkMessage)

    /** 收到语音二进制帧（AudioPacket.toFrame() 产物）。 */
    fun onAudioMessage(frame: ByteArray)

    /** 连接状态变化。 */
    fun onConnectionChanged(connected: Boolean)
}
