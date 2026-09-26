# Requirements Document

## Introduction

InkLink 是一款纯 Kotlin 开发的安卓双端软件，用于手表/备用安卓机与主力手机之间的实时联动。系统提供局域网投屏（文本/图片）、GPS 定位上报、电子围栏告警、双向语音对讲能力，并预留公网服务器中转接口。本版本将适配下限从 Android 7.1 下调至 Android 6.0（minSdk 23），新增语音对讲模块与全机型多分辨率屏幕适配，原有投屏、定位、围栏、服务器预留架构保持不变。

## Glossary

- **受控端（Controller）**：运行于安卓手表/备用安卓机的软件端，承担后台常驻、投屏内容接收显示、GPS 采集上报、围栏告警触发、语音拾音与播放。
- **主控端（Host）**：运行于主力手机的软件端，承担地图查看、电子围栏设置、指令下发、语音对讲发起、告警接收。
- **局域网直连模式**：受控端建立 WebSocket Server，主控端通过局域网 IP 直连，数据不经过公网。
- **公网中转模式**：受控端与主控端均连接公网中转服务器，通过设备 ID 路由消息。当前仅预留接口，本期不实现。
- **语音对讲**：基于 Android 原生 AudioRecord/AudioTrack 的实时语音传输能力，可插拔，与投屏、定位解耦。
- **InkMessage**：双端统一消息载体，承载文本、图片、GPS、指令、语音等全部消息类型。
- **消息类型（MessageType）**：消息协议中的类型枚举，每个类型携带唯一数字编码。
- **电子围栏（Geofence）**：主控端在地图上绘制的圆形区域，受控端进入/离开时上报告警。
- **前台服务（InkForegroundService）**：受控端常驻进程载体，托管 GPS、围栏、语音对讲等后台能力。
- **动态权限**：Android 6.0+ 运行时权限申请机制，权限缺失时对应功能降级禁用而非崩溃。

## Requirements

### Requirement 1: 系统兼容性

**User Story:** AS 用户，I want 软件在 Android 6.0 及以上的老旧设备、安卓手表、备用机上正常运行，so that 覆盖绝大多数存量安卓设备。

#### Acceptance Criteria

1. WHEN 受控端或主控端运行于 Android 6.0（API 23）及以上系统，系统 SHALL 以 `minSdk 23` 正常安装与运行。
2. WHEN 设备具备 GPS 硬件，系统 SHALL 启用定位与围栏模块。
3. WHEN 设备不具备 GPS 硬件，系统 SHALL 静默关闭定位模块并禁用围栏相关功能，同时主投屏功能保持可用。
4. IF 设备屏幕为圆形或异形（如部分安卓手表），系统 SHALL 对界面进行边缘适配以避免内容被遮挡。

### Requirement 2: 传输层

**User Story:** AS 用户，I want 受控端与主控端通过局域网 WebSocket 直连通信，并预留公网服务器中转能力，so that 无需公网即可使用且未来可扩展远程访问。

#### Acceptance Criteria

1. WHEN 受控端启动且处于局域网，系统 SHALL 启动 WebSocket Server 并等待主控端连接。
2. WHEN 主控端发起局域网连接，系统 SHALL 通过 WebSocket 建立双向消息通道。
3. WHEN 局域网连接建立后，系统 SHALL 支持文本、图片、GPS、围栏指令、语音、心跳全部业务消息传输。
4. IF 局域网连接断开，系统 SHALL 触发自动重连并在重连成功后恢复业务。
5. WHERE 公网中转场景，系统 SHALL 使用与局域网完全相同的消息结构，仅替换底层传输实现，上层业务无需区分底层模式。
6. WHEN 连接空闲，系统 SHALL 按心跳间隔发送心跳消息以保持连接存活。

### Requirement 3: 消息协议

**User Story:** AS 开发者，I want 双端使用统一的类型化消息协议，so that 新消息类型可扩展且语音与文本/图片可共存。

#### Acceptance Criteria

1. THE 消息协议 SHALL 提供类型枚举 `MessageType`，每个类型携带唯一数字编码，涵盖：文本投屏、图片投屏、GPS 位置上报、清屏指令、围栏配置、区域进入告警、区域离开告警、语音数据、语音通话开始、语音通话结束、心跳保活。
2. THE 消息载体 `InkMessage` SHALL 承载类型、文本载荷、语音二进制载荷、目标设备 ID、来源设备 ID、地址字段。
3. WHEN 传输文本、图片、GPS、围栏等业务消息，系统 SHALL 使用文本载荷字段承载。
4. WHEN 传输语音数据，系统 SHALL 使用二进制音频载荷字段承载，且不得与文本类消息语义冲突。
5. THE 协议 SHALL 为语音数据包、语音通话开始、语音通话结束分别定义独立消息类型。

### Requirement 4: 多分辨率屏幕适配

**User Story:** AS 用户，I want 软件在手机、方形/圆形手表、不同分辨率与屏幕比例的设备上界面正常，so that 全机型视觉体验一致。

#### Acceptance Criteria

1. THE 系统 SHALL 在全部布局中统一使用 `dp` 作为尺寸单位、`sp` 作为文字单位，禁止使用固定像素 `px`。
2. THE 页面布局 SHALL 优先使用 ConstraintLayout 约束布局以实现自适应。
3. THE 系统 SHALL 提供多套 `values/dimens.xml` 资源，对小屏、大屏、平板做尺寸微调。
4. THE 系统 SHALL 为图片资源提供 mdpi/hdpi/xhdpi/xxhdpi 多套密度资源。
5. WHEN 运行于手表等小屏设备，受控端 SHALL 隐藏非必要控件、简化侧边栏，并自适应投屏画布大小。
6. WHEN 运行于低分辨率设备，受控端 SHALL 自动降低图片投屏分辨率以减少内存占用。
7. THE 系统 SHALL 提供 `DensityUtil` 工具类封装屏幕密度、宽高获取与 dp/px 互转。

### Requirement 5: Android 6.0 动态权限

**User Story:** AS 用户，I want 敏感权限（定位、录音、存储）在运行时按需申请，so that 权限缺失时功能降级而非应用崩溃。

#### Acceptance Criteria

1. WHEN 受控端/主控端需要定位功能，系统 SHALL 运行时申请 `ACCESS_FINE_LOCATION` 权限。
2. WHEN 受控端/主控端需要语音对讲，系统 SHALL 运行时申请 `RECORD_AUDIO` 权限。
3. WHEN 受控端/主控端需要日志、图片缓存等能力，系统 SHALL 运行时申请 `WRITE_EXTERNAL_STORAGE` 权限。
4. WHEN 应用启动且必要权限未授予，系统 SHALL 弹窗引导用户申请。
5. IF 定位权限被拒绝，系统 SHALL 自动禁用 GPS 与围栏功能并保持其余功能可用。
6. IF 录音权限被拒绝，系统 SHALL 自动禁用并隐藏语音对讲入口。
7. THE 系统 SHALL 提供 `PermissionUtil` 工具类封装权限申请、结果回调与权限判断的通用逻辑。

### Requirement 6: 语音对讲模块

**User Story:** AS 主控端用户，I want 与受控端进行低延迟双向语音对讲，通话在后台 Service 中托管不依赖 Activity，so that 切屏或锁屏不中断通话。

#### Acceptance Criteria

1. WHEN 主控端用户触发发起通话，系统 SHALL 向受控端发送语音通话开始消息。
2. WHEN 受控端收到语音通话开始消息且具备录音权限，系统 SHALL 启动 AudioRecord 采集麦克风音频。
3. WHEN 受控端无录音权限，系统 SHALL 拒绝通话请求并回发不可用状态。
4. WHILE 通话进行中，采集端 SHALL 按约 20ms 时长分片采集 PCM 音频并通过 WebSocket 分片发送。
5. WHILE 通话进行中，双端 SHALL 同时采集与播放（全双工），收发互不阻塞。
6. WHEN 对端收到语音数据包，系统 SHALL 通过 AudioTrack 实时解码播放。
7. WHEN 任一端用户触发挂断，系统 SHALL 发送语音通话结束消息并释放音频资源。
8. THE 语音采集与播放 SHALL 全部托管于 `InkForegroundService` 前台服务，Activity 销毁不中断通话。
9. THE 语音采样率 SHALL 采用 8000Hz 以降低带宽占用并适配局域网弱网环境。
10. WHEN 采集端检测到静音，系统 SHALL 不发送语音数据包以节省流量与功耗。
11. IF 设备支持回声消除，系统 SHALL 优先启用原生 `AcousticEchoCanceler`；IF 硬件不支持，系统 SHALL 降级为降噪并提示用户佩戴耳机。
12. WHILE 通话中，双端 SHALL 实时同步通话状态（通话中/空闲）。
13. IF 任一端正在通话，系统 SHALL 拒绝或排队新的通话发起请求。
14. THE 语音对讲模块 SHALL 可在设置页开关，关闭后不影响投屏与定位功能。

### Requirement 7: GPS 定位与电子围栏

**User Story:** AS 主控端用户，I want 实时查看受控端位置并设置电子围栏，so that 受控端进出区域时收到告警。

#### Acceptance Criteria

1. WHEN 受控端开启且具备定位权限，系统 SHALL 周期采集 GPS 位置并上报主控端。
2. WHEN 主控端在地图上绘制电子围栏，系统 SHALL 将围栏配置下发给受控端。
3. WHEN 受控端进入围栏区域，系统 SHALL 触发进入区域告警消息。
4. WHEN 受控端离开围栏区域，系统 SHALL 触发离开区域告警消息。
5. WHEN 主控端收到告警消息，系统 SHALL 展示告警通知并触发震动提醒。
6. THE 围栏配置与告警消息 SHALL 复用通用消息协议传输。

### Requirement 8: 地图集成

**User Story:** AS 主控端用户，I want 在高德地图上实时查看受控端位置并可视化编辑围栏，so that 位置感知直观清晰。

#### Acceptance Criteria

1. THE 主控端 SHALL 集成高德地图 SDK 展示受控端实时位置。
2. THE 地图集成 SHALL 保留个人开发者 Key + 包名 + SHA1 校验机制。
3. THE 围栏绘制 SHALL 在地图上可视化完成。
4. THE 受控端 SHALL 保持轻量化，不集成地图 SDK。

### Requirement 9: 后台常驻与保活

**User Story:** AS 用户，I want 受控端在后台长期运行不被系统轻易杀死，so that 定位、告警、语音能力持续可用。

#### Acceptance Criteria

1. THE 受控端 SHALL 通过前台服务运行，并展示前台通知栏以提升进程优先级。
2. THE 系统 SHALL 提供电源白名单跳转引导，引导用户将应用加入后台白名单。
3. IF 系统进入休眠，系统 SHALL 调整心跳包间隔以适配休眠策略，防止连接断开。
4. WHILE 前台服务运行，系统 SHALL 持续承载定位采集、围栏监测、语音托管能力。

### Requirement 10: 主控端功能清单

**User Story:** AS 主控端用户，I want 一键连接设备并完成全部管控操作，so that 单应用即可完成对受控端的管理。

#### Acceptance Criteria

1. THE 主控端 SHALL 提供一键连接设备能力并支持发送图文控制指令与清屏指令。
2. THE 主控端 SHALL 提供实时位置查看与可视化电子围栏编辑。
3. THE 主控端 SHALL 在收到进入/离开围栏告警时展示通知与震动提醒。
4. THE 主控端 SHALL 提供双向语音对讲能力。
5. THE 主控端 SHALL 适配不同分辨率的手机屏幕。

### Requirement 11: 受控端功能清单

**User Story:** AS 受控端用户，I want 设备在后台稳定提供投屏、定位、告警、语音能力，so that 主人可随时远程查看与沟通。

#### Acceptance Criteria

1. THE 受控端 SHALL 支持局域网自动连接、图文投屏显示与清屏指令响应。
2. THE 受控端 SHALL 支持后台常驻保活，适配 Android 6.0+。
3. THE 受控端 SHALL 支持 GPS 定位上报与围栏进出告警，无 GPS 时自动关闭。
4. THE 受控端 SHALL 支持语音对讲接收与发送，无录音权限时自动禁用。
5. THE 受控端 SHALL 自适应各类手表、手机分辨率的屏幕。
