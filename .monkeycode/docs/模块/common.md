# common 公共库

双端唯一共享层：协议、传输、音频、聊天、定位围栏、工具。Android Library，不含任何 App 组件（Activity/Service），保证双端可复用。

## 结构

```
common/src/main/java/com/inklink/common/
├── protocol/            # 协议层
│   ├── MessageType.kt   # 52+99 码位枚举（fromCode Map 反查）
│   ├── InkMessage.kt    # 消息载体（msgId 幂等键，64KB 上限）
│   ├── MessageCodec.kt  # Gson JSON 编解码（剥离 audioData）
│   ├── ChatMessage.kt   # 聊天气泡单元
│   └── payload/         # 9 组载荷：PetPayloads/LearnPayloads/SoundPayloads(SoundProtocol)/
│                        # AckPayload/AlertPushPayload/DeviceStatusPayload/HeartbeatPayload/
│                        # ImagePayload/RingPayload
├── transport/           # 传输层抽象
│   ├── IMessageTransport.kt / TransportListener.kt / TransportMode.kt
│   ├── TransportManager.kt   # 三模式互斥切换 + pending 文本重放 + 64KB 校验
│   ├── LocalWsTransport.kt   # host=SERVER 8080 / controller=CLIENT，指数退避
│   ├── ServerRelayTransport.kt  # 公网中转 Client（服务端已删）
│   ├── AblyRelayTransport.kt    # 默认模式；好友频道 {33,34,43} 白名单
│   └── SslTrustAll.kt        # 仅测试用信任所有证书
├── audio/               # 实时语音
│   ├── AudioManager.kt  # 8000Hz/16bit/mono/20ms，AEC，静音检测，jitter 5 帧
│   └── AudioPacket.kt   # 二进制帧：[type=8][uint16序号LE][PCM]
├── chat/                # ChatStore(内存)/VoiceRecorder(AMR_NB)/VoicePlayer
├── service/
│   ├── gps/             # GpsDetector/GpsManager(双源+卡尔曼)/GpsReport/KalmanLocationFilter
│   ├── geofence/        # GeoFence/GeofenceConfig/GeoFenceManager(去抖 confirmCount=2)
│   └── discovery/       # UdpDiscovery(9000, INKLINK_DISCOVER/INKLINK_REPLY)
└── utils/               # PermissionUtil/DensityUtil/DeviceIdProvider/CoordinateConverter/
                         # HeartbeatPolicy/IdempotentController/MonoClock+MonoThrottle/
                         # ImageUtil/NetworkUtil/ReportThrottler
```

## 关键文件

| 文件 | 目的 |
|------|------|
| `protocol/MessageType.kt` | 全部码位唯一真值源；改协议先改这里 |
| `protocol/payload/SoundPayloads.kt` | SoundProtocol 双端音频契约（白名单/长度上限/sanitizeTts） |
| `transport/TransportManager.kt` | 上层唯一入口；切换与断线重放全在这 |
| `audio/AudioPacket.kt` | 语音二进制帧格式，改格式必同步双端 |
| `utils/MonoClock.kt` | 单调时钟 + MonoThrottle；一切时长/冷却计时用它 |

## 依赖

**本模块对外 api**：Java-WebSocket、slf4j-android、gson、kotlinx-coroutines-android、core-ktx
**implementation**：ably-android 1.2.52
**被依赖**：app-host、app-controller（project(:common)）

## 规范

- 本模块禁止引用任何 Android UI 组件；上下文仅用于系统服务（定位/音频/SharedPreferences）
- 新增码位/载荷流程见 DEVELOPER_GUIDE「新增消息码位」；重复 code 由 MessageTypeTest 拦截
- 一切与墙钟相关的逻辑用 MonoClock；冷却/去重窗口必须有时钟回拨回归测试（参考 MonoThrottleTest）
- 单测 9 类：协议往返/唯一性、SoundProtocol 契约、AudioPacket、围栏去抖、坐标转换、幂等、单调节流

## 添加新文件

### 新增载荷

1. 放 `protocol/payload/`，纯 data class，字段带默认值（旧端 Gson 缺省兼容）
2. 补 round-trip 单测（参考 PetProtocolRoundTripTest 的旧端缺省字段用例）
