# 全面代码复盘报告（2026-10-04）

> 范围：history-sync 四批次（设备管理/历史轨迹补传）交付后，对 `common` / `app-host` / `app-controller` / `tools` 的全量复审。
> 方法：三路并行模块复审 → 逐项代码验证 → 按 Blocker/Major/Minor 分级修复。本文件是修复台账，也是后续 AI 接手的复查基线。

## 1. Blocker（功能完全失效，已修）

| # | 问题 | 位置 | 修复 |
|---|------|------|------|
| B1 | 识字屋答错后 `answered=true` 拦截全部按钮且 `idx` 不前进，当前关卡永久卡死 | `HanziActivity.kt` 错答分支 | 对齐口算屋模式：高亮正解 + `idx++` + 延时重渲染 |
| B2 | 拼音屋同款锁死（错答只置 answered 不推进） | `PinyinActivity.kt` 错答分支 | 同上 |
| B3 | 古诗屋答错后仍可继续点选，连点错项虚增分母，准确率归零 | `PoemActivity.kt` | 加 `quizAnswered` 守卫 + 高亮正解 + 前进 |

**教训**：学习页答题三件套（答错必须：锁输入 + 高亮正解 + 推进索引）在三岛各自实现过三遍，仍出现两次遗漏。新增答题页先抄 `MathActivity`。

## 2. Major（正确性/安全/资源，已修）

### common

| # | 问题 | 位置 | 修复 |
|---|------|------|------|
| C1 | 音频采集缓冲 `buffer` 复用，`readBytes` 与播放线程读同一数组产生竞态撕裂音 | `AudioManager.kt:99` | 始终 `buffer.copyOf(read)` |
| C2 | ReportThrottler 用 `System.currentTimeMillis()` 做冷却/去重窗口，墙钟回跳功能静默失效 | `ReportThrottler.kt` | 重写：默认 `MonoClock.now()`，Haversine 委托 `GeoFenceManager` |
| C3 | LocalWs/ServerRelay/Ably 三传输 `disconnect()` 不关 executor，反复重连线程泄漏 | 三个 Transport | 补 `shutdownNow()`；ServerRelay/Ably 补 `listener?.onConnectionChanged(false)` |
| C4 | UdpDiscovery responder 绑定失败后 `running` 残 true，发现功能静默死亡 | `UdpDiscovery.kt` | 失败复位 `running=false` + `Log.w`，finally 置 bound |
| C5 | GeoFenceManagerTest 两个多围栏用例与 2-sample 去抖实现矛盾，自 f4977c8 起常红——因 common 测试从未进 CI 而隐形 | `GeoFenceManagerTest.kt` | 按实际语义重写 |
| C6 | `distanceMeters` 用 `atan2(sqrt(a),sqrt(1-a))`，对径点因浮点负值出 NaN；且三处 Haversine 重复拷贝 | `GeoFenceManager.kt` | `2*asin(sqrt(a.coerceIn(0,1)))`，ReportThrottler/GpsTreasureHunter 统一委托 |
| C7 | CI 门禁只跑 app-host/app-controller 测试，common 模块测试从不执行 | `.github/workflows/android.yml` | 补 `:common:testDebugUnitTest` + 报告上传。**补上门禁后立即抓出两个问题**（93ac503 修复）：ControllerActivity `when` 缺 else 编译失败；GeoFenceManagerTest 第 103 行断言漏了同采样触发的 `Exit(b)` |
| C8 | ChatStore `++seq` 非原子，网络线程+UI 双写发重复消息 id | `ChatStore.kt` | `AtomicLong` |

### app-host

| # | 问题 | 位置 | 修复 |
|---|------|------|------|
| H1 | onResume 先置 `PetClock.foreground=true` 再 settle，整段后台时间被按前台速率结算 | `PetMainActivity.kt:2223` | 交换顺序：先 settle 后翻转 |
| H2 | PetWakeupReceiver.scheduleNextCheck 只在 onReceive 内调用，首次安装/重启后促活链永不启动 | `PetWakeupReceiver.kt` | service onCreate 初始调度 |
| H3 | pairingKey 硬编码默认 `inklink_default_key`：频道名可预测 + 远程重置 PIN 的 HMAC 可伪造；且无任何修改入口 | `InkHostApplication.kt` 等 | 两端加 setter + 设置页「配对密钥设置」+ 默认密钥警告 |
| H4 | PetClock.FLOOR 死逻辑（后台已改自动休眠，floorVal 可达路径恒 0）且 KDoc 停留在 ÷20+地板30 旧方案 | `PetClock.kt`/`PetDecayEngine.kt` | 删 FLOOR 常量，各属性 0 地板并注释钉死 |
| H5 | LearningHub cardFocus（舒尔特方格）绕过 `guardPass()` 护眼三规则 | `LearningHubActivity.kt:78` | 补守卫 |
| H6 | 响铃横幅 dismiss 只清 UI 标志，SirenManager 持续播放，用户无法手动止损 | `HostActivity.kt:149` | 发 `ACTION_STOP_RING` 通知 service 停播 |

### app-controller

| # | 问题 | 位置 | 修复 |
|---|------|------|------|
| K1 | 定位权限全端从未申请，主控端自身绿色标记/驾车路线静默失效 | `ControllerActivity.requestPermissions`/`MapActivity` | PermissionUtil 增 LOCATION 组合；启动申请；MapActivity 未授权不采集（防 SecurityException） |
| K2 | PetDetailActivity 异步消息弹窗无生命周期守卫，关页后 AlertDialog 抛 BadTokenException | 3 处 runOnUiThread | 加 `safeUi {}`（isDestroyed/isFinishing 双检） |
| K3 | DeviceRepository list()+save() 读-改-写并发互相覆盖（UDP 发现 + 手动添加） | `DeviceRepository.kt` | 公开方法 `@Synchronized` |
| K4 | 连接建立不发宣告，受控端默认目标空 → 不发心跳 → 看护提醒误报离线（局域网首启尤其明显） | `InkControllerApplication` + host `controllerCommandTypes` | 连接即发 PING（带 deviceId），host 将其纳入目标学习类型 |
| K5 | 状态文案硬编码「Ably 4G 在线」，切局域网/中转后显示错误 | `ControllerActivity.render` | 按 currentMode 派生 |
| K6 | 子页返回（设备管理/地图）不刷新选中态 | `ControllerActivity` | onResume render() |
| K7 | ChatActivity 按住录音键直接退出泄漏 MediaRecorder 与临时文件 | `ChatActivity.kt` | onDestroy 收尾；VoiceRecorder 读完删临时文件 |
| K8 | typeEmoji 硬编码 17 物种，frog/pig/owl/snake 回落 🐾 | `PetMainActivity.kt` | 委托 PetCatalog 唯一数据源 |
| K9 | PIN 哈希 `==` 比较存在时序侧信道 | `PinSecurityManager.kt` | `MessageDigest.isEqual` 恒定时间比较 |

## 3. 复审中确认不成立/已有防护的项（防止重复排查）

- tools/ 五条资产管线为纯 Python 离线脚本（3073 行），不进 App 运行时，本轮巡检无代码级风险点；构建配置（gradle/libs.versions.toml/settings）无问题。
- FenceEditActivity 已有空围栏/无目标校验（非无校验）。
- PetDetailActivity 监听链已做 save/restore（非泄漏）。
- InkMessage.msgId 工厂与主构造默认值都会生成（非可空）。
- DeviceDetailActivity 文档与实际选项均为 1/3/7 天。
- 代码中不存在 route SenderExceeded 计数器、pendingChatImages、SleepModeManager——复审初报的三项为误报。
- UdpDiscovery 绑定失败已通知（Log.w）+ service 侧有日志链路，app 层尚无 UI 提示，见遗留 L1。

## 4. 遗留 Minor（第二轮处置记录）

| # | 事项 | 状态 |
|---|------|------|
| L1 | UdpDiscovery 失败仅日志，无 UI 提示 | **澄清关闭**：controller 侧从未接入 UDP 扫描（配对走二维码），无 UI 提示路径；responder 失败已有 `running` 复位+日志；顺手删除 ControllerActivity 两个未用 import |
| L2 | McAudioRecorder / McAudioApiSample 过时 API | **保留**（现代化改造面大，无功能收益） |
| L3 | ImageUtil 尺寸/质量契约注释与实现细节需对齐 | **已修**：缩到下限仍超限返回 null（不再产出必被丢弃的脏数据），三处发送点补提示 toast，中间位图回收，KDoc 与实际算法对齐 |
| L4 | GpsManager 不可达分支 | **已修**：quality 分支去掉 `> 50f` + 不可达 `NO_GPS` |
| L5 | TransportManager.sameTarget 早退不刷新 heartbeatIntervalProvider | **已修**：sameTarget 先刷新 provider；`heartbeatIntervalProvider` 上提 `IMessageTransport` 接口 |
| L6 | VoicePlayer 同步文件 IO 在主线程调用点 | **已修**：IO 线程落盘 → 主线程创建 MediaPlayer（Looper 纪律，踩过坑），临时文件随下次播放/停止删除 |
| L7 | RawSoundPlayer.releaseActive 无完成回调，连发音效可能截断 | **已修**：新增 `Result.INTERRUPTED`，被打断时先兑现旧回调（否则 ACK 悬挂）；PetAudioFeedback 被打断不再接读（避免叠音） |
| L8 | PinSecurityManager 盐为静态常量 `inklink_salt_`（彩虹表风险） | **已修（零失效迁移）**：setPin 改用每 PIN 随机盐（hex）持久化；存量静态盐哈希首次校验通过后透明迁移为随机盐；resetPinRemote 收到对端计算的哈希时按遗留盐约定存储后再迁移。时序侧信道此前已修（`MessageDigest.isEqual`） |
| L9 | 死代码：showDailyGoalSheet / dueReviewCount / SoundEffectManager.soundPool | **澄清+已删**：dueReviewCount/soundPool 实际在用（误标）；showDailyGoalSheet 确为死代码（功能已在背包页接线），已删函数 |
| L10 | WrongBookActivity 难度写死 | **已修**：`WrongItem` 带出 `level`（取答错时的关卡），替换写死的 2 |
| L11 | ChatActivity(controller) 对话框守卫 / HistoryClientTest 无缝缓冲用例 / MapActivity 视角切换未接线 | **澄清+补测**：异步路径早有 isDestroyed/isFinishing 守卫、无视角切换按钮；已补"受控端重生成更小分块集"无缝衔接用例 |
| L12 | TrackStore.cleanup() 内 synchronized 无实际临界区 | **澄清关闭**：现行 cleanup 无 synchronized（复审后已变） |

## 5. AI 接手必读（本轮强化）

1. **CI 必须覆盖 `:common`**：本轮 C5/C7——GeoFenceManagerTest 红用例因 common 测试不进 CI 潜伏了大半年。
2. **墙钟禁令**：冷却/限流/去重窗口一律 `MonoClock.now()`（`SystemClock.elapsedRealtime()`）。
3. **答题三件套**：新答题页先抄 `MathActivity` 错答分支（锁输入 + 高亮正解 + 推进）。
4. **双时钟 settle 顺序**：翻转 `PetClock.foreground` 前后各 settle 一次，顺序反了整段按错倍率结算。
5. **配对密钥两端一致**：host 设置 → 配对密钥设置；controller 设置 → 配对密钥设置。改任一端的 key 必须同步另一端，否则 ABLY 频道名不匹配、直接失联。
6. **弹窗/对话框异步出口**：一律 `isDestroyed/isFinishing` 双检后再 show。
7. **MediaPlayer 必须创建在有 Looper 的线程**（通常是主线程）：其构造函数内部 `new Handler()` 取当前线程 Looper，工作线程上直接抛 "Can't create handler inside thread that has not called Looper.prepare()"；需要 IO 时"后台落盘 → 主线程创建"，参考 `VoicePlayer`/`RawSoundPlayer`。
8. **接口上提取公共能力属性**：`heartbeatIntervalProvider` 原在三传输类各写一份，`TransportManager.sameTarget` 早退时就漏刷新——上提 `IMessageTransport` 接口后单点刷新，同类"早退不同步"坑一次根除。
