package com.inklink.common.transport

enum class TransportMode {
    /** 局域网直连：app-host 起 WS Server，app-controller 直连 */
    LOCAL,

    /** 公网中转：双端均主动连接服务器 */
    RELAY,

    /** Ably 中转（临时原型）：双端连 Ably 广播频道，客户端过滤模拟点对点 */
    ABLY
}
