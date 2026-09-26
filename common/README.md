# InkLink Common

InkLink 应用的共享 Android 基础库，被 `app-host`（受控端）与 `app-controller`（主控端）以 Git Submodule 方式引入。

## 包含能力

- **协议与数据包**：`InkMessage` / `MessageCodec` / `MessageType`（含聊天 `CHAT_TEXT`、`CHAT_IMAGE`、`CHAT_AUDIO`、主动拉取位置 `REQUEST_GPS` 与心跳 `HEARTBEAT` 等消息指令）
- **聊天支持**：`ChatStore`（内存级聊天消息流管理器）、`VoiceRecorder`（AMR_NB 录音并控制体积）、`VoicePlayer`（音频播放）
- **定位**：`GpsManager`（持续上报与 `requestSingleUpdate` 单次即时采集）/ `GpsDetector` / `GpsReport` / `CoordinateUtil`（WGS-84 与 GCJ-02 转换）
- **围栏**：`GeoFenceManager`（本地 Haversine 判定）/ `GeofenceConfig`
- **传输**：`TransportManager` + 本地 WebSocket（`LocalWsTransport`）/ 公网中继（`ServerRelayTransport`）/ Ably 云中继（`AblyRelayTransport`）
- **音频**：`AudioManager` / `AudioPacket`（实时通话采集、PCM 播放与抖动缓冲）
- **工具**：`DeviceIdProvider` 设备唯一识别、`HeartbeatPolicy` 心跳策略、`PermissionUtil`、`NetworkUtil`、`ImageUtil` 图片压缩与 Base64 编解码

## 版本

- Kotlin 1.9.24，AGP 8.2.2，compileSdk 34，minSdk 23
- 模块命名空间：`com.inklink.common`

## 作为 Submodule 引入

```bash
git submodule add https://github.com/caogenfunan123/inklink-common.git common
```

宿主工程的 `settings.gradle.kts` 中 `include(":common")` 即可引用。

## 仓库清单

- `inklink-common`（本仓库）：共享基础库
- `inklink-host`：受控端 App
- `inklink-controller`：主控端 App
