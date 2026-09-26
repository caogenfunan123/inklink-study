# InkLink 开发任务拆解清单

> 基于整体开发顺序拆分的可执行任务。每个任务含预估工期与输出物，勾选跟踪进度。
> 工程模式：`common` 公共库 + `app-host`（受控端）+ `app-controller`（主控端）三 Module，放弃单模块 productFlavor 双包名，规避老旧 Gradle 版本坑。

## 当前进度总览

- 代码实现：T0-T7 全部完成，T8/T9/T10 代码完成、真机/线上部署待执行。
- 编译验证：`gradle assembleDebug` 已通过，产出 `app-host-debug.apk`（约 4.2M）与 `app-controller-debug.apk`（约 40M，含腾讯地图 SDK）；`common` 与 `app-controller` 单元测试全部通过（共 30 个，0 失败）。
- 未完成项（需真机/真实服务器/构建产物）：T8 白名单引导与真机测试、T9 云服务器部署/签名/release APK、T10 真机 4G-4G 联调与 release APK 重建。
- 已知风险：腾讯地图鉴权需 Key + 包名 + SHA1 三者严格匹配，控制台须绑定 Debug/Release 两套 SHA1，否则鉴权失败；release 签名与 CI 已配置统一 keystore（`inklink-release.keystore`）；Ably 为 4G-4G 临时原型，自建 WS 上线后整体移除。

## 任务 0：工程骨架搭建（T0，预估 0.5 天）

- [x] 新建 Android 项目，创建 3 个 Module
  - `common`：Android Library，minSdk 23，不含 App 组件，存放 transport/protocol/utils/audio 公共代码
  - `app-host`：Application 模块，依赖 common，不引入地图 SDK，受控端
  - `app-controller`：Application 模块，依赖 common，引入腾讯地图 Android SDK，主控端
- [x] 统一配置：compileSdk、targetSdk 用 Version Catalog（`gradle/libs.versions.toml`）单点控制；两个 app 模块 minSdk=23
- [x] 包名配置：app-host = `com.inklink.host`，app-controller = `com.inklink.controller`
- [x] AndroidManifest 基础声明：网络、唤醒锁、定位、录音权限；声明 `InkForegroundService` 前台服务；开机广播接收器 receiver
- [x] common 内新建基础包目录：transport、protocol、utils、audio
- [x] Gradle 依赖：websocket 加到 common；腾讯地图仅加到 app-controller
- [x] git 初始化，`.gitignore` 配置

**输出物**：工程可编译、无报错、模块依赖关系正确。

## 任务 1：协议层开发（T1，预估 0.5 天）

- [x] common/protocol：完成修复编号的 `MessageType` 枚举
- [x] 实现 `InkMessage` 数据类：payload 文本、audioData 二进制数组、服务器预留字段
- [x] 实现序列化/反序列化：JSON 处理文本消息；二进制消息分离字节流
- [x] 编写单元测试：round-trip 往返测试，序列化后反序列化对象一致
- [x] 校验：全部消息类型枚举完整、无编号冲突

**输出物**：协议单元测试通过；双端共用一套消息模型。

## 任务 2：传输层开发-局域网 WebSocket（T2，预估 1-1.5 天）

- [x] common/transport：定义 `TransportMode`、`IMessageTransport` 完整接口
- [x] 实现 `LocalWsTransport`
  - app-host 侧：启动 WebSocket 服务端，监听 8080 端口
  - app-controller 侧：WebSocket 客户端，连接 host IP:8080
  - 消息分发、接收回调、连接状态回调
  - 心跳 HEARTBEAT 收发、断线检测
- [x] `ServerRelayTransport` 完整实现：文本/二进制帧分流、设备 ID 上报注册、指数退避重连
- [x] 最小链路联调：主控发送 TEXT 消息，受控端收到打印日志
- [x] 异常处理：网络断开、网络切换、端口占用

**输出物**：文本投屏最小链路跑通，可收发 TEXT 消息。

## 任务 3：双端 UI + 多分辨率屏幕适配（T3，预估 1.5-2 天）

### app-host（受控端 UI）

- [x] 全屏投屏 Activity：隐藏状态栏导航栏，画布自适应屏幕
- [x] 右上角透明热区，呼出竖向侧边抽屉菜单
- [x] 侧边栏页面：连接设置、深度刷新、运行日志入口、跳转 WiFi 系统设置
- [x] 待机页面：二维码、IP 端口展示；已连接等待推送提示
- [x] 内存环形日志（内存 50 条，重启清空）

### app-controller（主控端 UI）

- [x] 设备扫描/手动输入 IP 连接页面
- [x] 控制面板：发送文本、发送图片、清屏按钮
- [x] 设置页面：传输模式选择（局域网默认，服务器中转可选）

### 适配规范强制落地

- [x] 全部布局 ConstraintLayout；尺寸 dp、文字 sp，禁止硬编码 px
- [x] `DensityUtil.kt` 加入 common/utils：dp-px 转换、获取屏幕宽高
- [x] 方形/圆形手表预留 UI 边界避让逻辑

**输出物**：双端 UI 可运行；投屏画布自适应不同屏幕尺寸。

## 任务 4：权限层封装 PermissionUtil（T4，预估 0.5 天）

- [x] common/utils 实现 `PermissionUtil.kt`：定位、录音、存储权限申请工具
- [x] 判断权限是否拥有；申请权限回调封装
- [x] 权限拒绝回调：对应功能模块自动降级禁用
- [x] app-host：启动时检测 GPS、录音权限
- [x] app-controller：检测定位、录音权限
- [x] 校验：拒绝录音 → 语音对讲按钮置灰；拒绝定位 → GPS 模块关闭

**输出物**：6.0 动态权限完整闭环，权限缺失功能自动降级，App 不崩溃。

## 任务 5：GPS + 本地电子围栏模块（T5，预估 1-1.5 天，仅 common+app-host）

- [x] common/service/gps：`GpsDetector.kt` 判断设备硬件/权限是否支持 GPS
- [x] `GpsManager.kt`：GPS 采集管理，定时上报 GPS_REPORT 消息
- [x] common/service/geofence：`GeoFence.kt` 数据模型；`GeoFenceManager.kt` 本地围栏判断
- [x] 去抖逻辑：进出围栏防抖，避免抖动反复告警
- [x] 受控端接收 `GEOFENCE_CONFIG` 消息更新围栏配置
- [x] 触发进出条件发送 `ALERT_ENTER` / `ALERT_EXIT` 告警消息
- [x] 单元测试：围栏进出判定逻辑单测

**输出物**：受控端 GPS 采集、围栏告警逻辑完成；无 GPS 硬件模块静默关闭。

## 任务 6：主控端腾讯地图接入（T6，预估 1-1.5 天，仅 app-controller）

- [x] Gradle 引入腾讯矢量地图 SDK（`tencent-map-vector-sdk` + `foundation`），只加到 app-controller
- [x] Manifest 配置 `TencentMapSDK` meta-data；补 `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` 权限
- [x] `InkControllerApplication` 隐私合规初始化（`setAgreePrivacy` → `start`）
- [x] `MapActivity`：地图页面，接收受控端 GPS_REPORT，绘制 Marker 标记设备位置
- [x] `FenceEditActivity`：地图打点，编辑圆形围栏参数，绘制 Circle，下发 GEOFENCE_CONFIG 给受控端
- [x] 腾讯地图混淆白名单写入 proguard-rules.pro
- [x] 接收告警消息：弹窗 + 系统通知提醒
- [ ] 逆地理编码【延后迭代】：暂不接入 `tencent-map-search-sdk`；`InkMessage.address` 协议字段保留预留，本期业务不填充

**开发提示**：控制台新建 Key 绑定包名 `com.inklink.controller` + Debug/Release 两套 SHA1；Debug 日常开发，Release 对应 CI 的 `inklink-release.keystore`。地图黑屏/不渲染优先排查 Key、包名、SHA1 是否匹配。

**输出物**：主控端地图展示、围栏编辑、告警通知完整可用。

## 任务 7：双向语音对讲模块（T7，高风险里程碑，预估 2-3 天，common 双端共用）

- [x] common/audio：`AudioPacket.kt` 音频数据包封装
- [x] `AudioManager.kt` 封装 AudioRecord 采集、AudioTrack 播放
- [x] 参数：8000Hz PCM 分片，20ms 一帧；静音检测
- [x] 集成到 `InkForegroundService` 前台服务，Activity 销毁音频不中断
- [x] MessageType：AUDIO_START / AUDIO_STOP / AUDIO_DATA 消息完整收发
- [x] app-host：收到 AUDIO_START 启动拾音；接收 AUDIO_DATA 播放音频
- [x] app-controller：UI 增加对讲按钮，发起/结束通话
- [ ] 弱网模拟测试：丢包容错；回声处理；权限缺失自动禁用对讲（代码已实现，待真机/弱网环境验证）

**输出物**：双向语音对讲，后台锁屏状态通话正常。

## 任务 8：保活、心跳自适应、多机型真机测试（T8，预估 1-2 天）

- [x] InkForegroundService 完善前台通知
- [x] 心跳包自适应间隔，休眠场景优化
- [ ] 引导跳转电源优化白名单页面
- [x] 断线自动重连逻辑完善
- [ ] 真机测试：老旧安卓手表（方形）、圆形手表（UI 边界避让）、普通安卓手机
- [ ] 测试场景：锁屏、后台、杀后台、网络切换

**输出物**：后台保活稳定，锁屏不丢连接。

## 任务 9：公网服务器中转适配 + 打包发布（T9，预估 1-1.5 天）

- [ ] Ubuntu 基础环境初始化（apt、Node.js、ufw + 云安全组端口放行 8081）
- [x] 部署修正版 server.js（isBinary 分流透传、幂等 upsert、服务端心跳 ping + 僵尸连接超时清理）+ systemd 开机常驻配置
- [ ] Nginx 配置 WSS 代理（443 端口）+ SSL 证书（Let's Encrypt，配置兼容旧安卓的完整证书链）——已提供 `nginx.conf.example`，待实际部署
- [x] 实现完整 `ServerRelayTransport.kt`：文本/二进制帧分流、设备 ID 上报注册
- [x] 设备 ID 逻辑：`DeviceIdProvider`（ANDROID_ID 优先 + UUID 持久化 fallback）
- [x] 指数退避重连（1s→2s→4s→上限 60s + jitter）、服务器抖动防重连风暴
- [x] 双模式切换隔离：`TransportManager.switchMode()` 先销毁旧实例再建新实例，切换期消息缓存重放
- [x] WSS 证书校验开关（自用调试可关，生产默认开启）
- [ ] 公网全链路联调：投屏、GPS、围栏告警、双向语音对讲跨外网传输
- [x] 配置两个模块签名配置：统一 keystore `keystore/inklink-release.keystore`（alias `inklink`），两端 App 共用同一 release 签名；密码存 `local.properties`（本地，gitignore 忽略）+ GitHub Secrets（CI）
- [x] 输出 debug APK：`app-host-debug.apk`、`app-controller-debug.apk`（`gradle assembleDebug` 已产出）；release 签名打包由 GitHub Actions CI 自动构建（push 触发）

**输出物**：可编译输出两套 APK，公网接口完整可用。

## 任务 10：Ably 中转 + 坐标转换 + 多设备管理（T10，预估 2-3 天）

### T10-a：WGS-84 ↔ GCJ-02 坐标转换（common）

- [x] 新增 `CoordinateConverter.kt`：WGS-84（GPS 原生）↔ GCJ-02（腾讯地图）双向转换，含中国境内判定
- [x] 接入 MapActivity：受控端 GPS_REPORT 展示前 WGS-84 → GCJ-02
- [x] 接入 FenceEditActivity：下发围栏前 GCJ-02 → WGS-84
- [x] 单元测试 `CoordinateConverterTest`：上海点偏移方向与量级断言修正后通过

### T10-b：Ably 公网中转（4G-4G 临时原型）

- [x] common 引入 `io.ably:ably-android:1.2.52` 依赖，补混淆白名单
- [x] 新增 `AblyRelayTransport.kt`：实现 `IMessageTransport`，Ably 广播频道 + `targetDeviceId` 客户端过滤
- [x] `TransportMode` 新增 `ABLY`；`TransportManager.switchMode` 增加 `ablyKey` 与 ABLY 分支
- [x] 两端 `build.gradle.kts` 注入 `BuildConfig.ABLY_KEY`，Key 经 `local.properties` 注入不硬编码
- [x] HostActivity 传输模式三选一 UI + 中继配置对话框
- [ ] 真机 4G-4G 联调：文字投屏、GPS、围栏告警跨外网传输（语音本期搁置）

### T10-c：主控端多设备管理

- [x] 新增 `DeviceEntity`（deviceId + 备注）+ `DeviceRepository`（SharedPreferences + JSON 持久化）
- [x] `ControllerState` 聚合多设备位置（`gpsByDevice`）、选中目标（`selectedDeviceId`）、在线判定（60s 阈值）
- [x] `InkControllerApplication` 增加设备 CRUD、Ably 连接、按 `fromDeviceId` 路由 GPS/告警
- [x] 新增 `DeviceManageActivity`：设备列表/添加/删除/重命名/点选目标
- [x] MapActivity 多设备 Marker 同显（按 deviceId 哈希分配颜色，优先聚焦选中设备）
- [x] FenceEditActivity 使用选中设备最新位置为初始中心，未选中目标禁止下发围栏
- [x] 受控端 `InkForegroundService` 修复：仅主控端指令类消息更新 `defaultTargetDeviceId`，避免多受控端广播串扰
- [x] 单元测试 `ControllerStateTest`、`DeviceRepositoryTest` 通过
- [ ] 真机联调：多台受控端绑定、地图同显、独立围栏、定向告警

**输出物**：主控端可管理多台受控端，Ably 临时中转可跨外网收发文本/JSON，坐标转换闭环。

## 开发优先级提示

1. T0-T3 优先跑通投屏闭环，第一阶段完成：文字投屏、图片投屏、清屏。语音是高风险后置里程碑，不要一开始陷入音频调试。
2. 模拟器不能替代手表真机，圆形屏幕、低内存必须真机验证。
3. 受控端禁止引入地图 SDK，依靠 common 库做代码复用，保证受控 APK 轻量化。

## 相关文档

- 需求文档：`requirements.md`
- 技术设计：`design.md`
- 整体架构：`.monkeycode/docs/ARCHITECTURE.md`
