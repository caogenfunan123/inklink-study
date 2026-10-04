package com.inklink.common.transport

import com.inklink.common.protocol.InkMessage

/**
 * 传输层统一接口。局域网与公网中转实现同构，上层业务只依赖本接口。
 */
interface IMessageTransport {

    /** 建立连接并开始收发。 */
    fun connect()

    /** 主动断开连接，释放资源。模式切换前必须先调用。 */
    fun disconnect()

    fun isConnected(): Boolean

    /** 发送文本消息。 [targetId] 为公网模式下的目标设备 ID，局域网模式下可忽略。 */
    fun sendMessage(message: InkMessage)

    /** 发送语音二进制帧。 */
    fun sendAudio(frame: ByteArray)

    fun setListener(listener: TransportListener?)

    /**
     * 心跳间隔提供器，透传到底层实现，用于亮屏/灭屏自适应。
     * 实时读取（每次排下一轮心跳时 invoke），引用变更即时生效。
     */
    var heartbeatIntervalProvider: (() -> Long)?
}
