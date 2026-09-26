# InkLink-Pet 最终完整架构与实施方案基准规范

> **核心数据流原则**：`InkForegroundService` 为唯一真相源（Truth Source）；UI 层仅订阅展示事件，禁止 UI 直接修改业务数据。
>
> **版本 V1.1｜部件化像素宠物**：10物种 / 五阶段成长+成年4形态 / 双时钟秒级数值 / 健康系统 / 性格+AI行为树 / 事件日志+随机事件+里程碑+每日目标 / 像素房间场景 / 主控→受控三类礼物（道具·装饰·Buff）/ Ably公网+局域网双通路 / Room持久化 / minSdk23 Kotlin。
> **终稿全文见文末「九、游戏化终稿规范 V1.1」**（与阶段8/9/10 tasklist 对齐；本规范之前章节为阶段一至七历史记录，其中与 V1.1 冲突的条目——如"禁止 emoji 图标"——以 V1.1 裁决为准）。

---

## 一、 系统整体架构与职责边界

```
┌─────────────────────────────────────────────────────────────┐
│                    表现层 (UI Layer)                        │
│  - PetMainActivity: 像素 HUD(分段胶囊进度条)、双行操作坞、   │
│    像素气泡弹窗(废弃原生Toast)、日志/里程碑/场景入口          │
│  - 宠物渲染【MAIN】: 部件化程序化像素引擎(96x96画布整数放大, │
│    头/身/手脚/眼/嘴部件 + 头饰/背饰/尾饰插槽 + LUT换色)      │
│    Rive 依赖保留、后置评估可选；PNG部件为 manifest 替换插槽  │
│  - 辅助系统: PetAiEngine行为树、SoundEffectManager(8bit音效)、│
│    LocalTtsManager、Konfetti粒子                            │
│  - 纯展示与交互: 订阅 Flow 驱动视图，业务数据由后台统一管理  │
└──────────────────────────────┬──────────────────────────────┘
                               │ MutableSharedFlow 事件总线
┌──────────────────────────────▼──────────────────────────────┐
│                  管控安全层 (Admin Layer)                   │
│  - HostSettingsActivity: 原受控端通信、定位与系统日志配置   │
│  - PinSecurityManager: 长按 3s 唤起、EncryptedSP 哈希持久化 │
│  - 防暴力破解: 输错 5 次持久化锁定 120s (重启保持锁定)      │
└──────────────────────────────┬──────────────────────────────┘
                               │ 进程内解耦调用
┌──────────────────────────────▼──────────────────────────────┐
│                 常驻后台服务 (Service Layer)                │
│  - InkForegroundService (唯一真相源)                         │
│  - Ably/WS 双向通信、30s PING / 45s 超时判定与重连同步       │
│  - GPS 采样与电子围栏判定 (无 GPS 模块静默降级)             │
│  - 强控响铃: MediaPlayer STREAM_ALARM + 强震动兜底          │
│  - 本地落盘: pet_bag.json、task_list.json、离线队列 (上限50) │
└─────────────────────────────────────────────────────────────┘
```

---

## 二、 完整通信协议分配表 (`MessageType`)

| 消息常量 | 指令码 | 方向 | 描述 | Payload 核心字段 | 备注 |
| :--- | :---: | :---: | :--- | :--- | :--- |

> **⚠️ 码位冲突记录**：旧协议已占用 `10(AUDIO_STOP)`、`11(REQUEST_GPS)`、`12(CHAT_TEXT)`，宠物心跳协议改用 `50/51/52`。enum 中重复码位会使 `fromCode` 静默覆盖（后定义覆盖先定义），导致旧功能消息在解码时被吞。
| `PING` | 50 | 受控 $\to$ 主控 | 周期心跳保活 | `deviceId`, `timestamp` | 受控端每 30s 发送一次 |
| `PONG` | 51 | 主控 $\to$ 受控 | 心跳应答 | `timestamp` | 超 45s 未收到应答判定离线 |
| `ERROR_RESPONSE` | 52 | 双向 | 通用错误回执 | `refMsgType`, `errorCode`, `message`, `timestamp` | 鉴权失败/参数非法通用回执 |
| `PET_STATE_SYNC` | 30 | 受控 $\to$ 主控 | 活跃宠物状态同步 | `activePetId`, `hunger`, `happiness`, `clean`, `energy`, `level`, `exp`, `timestamp` | 本地状态变化 3s 节流上报 |
| `PET_INTERACT_CMD` | 31 | 主控 $\to$ 受控 | 下发互动指令 | `action(FEED/PLAY/CLEAN)`, `foodType`, `count(1-5)`, `triggerTs` | 支持主控端长按防抖合并 |
| `PET_INTERACT_ACK` | 32 | 受控 $\to$ 主控 | 互动执行反馈 | `success`, `delta`, `petSnapshot`, `ackTs` | 返回执行增量与最新快照 |
| `PET_GAME_INVITE` | 33 | 双向 | 远程小游戏对战邀请 | `gameType(RPS)`, `inviteId`, `timestamp` | 远程猜拳对战流程 |
| `PET_GAME_ACTION` | 34 | 双向 | 远程小游戏出招动作 | `inviteId`, `actionData`, `timestamp` | 对战揭晓判定 |
| `PET_ALERT_EVENT` | 35 | 受控 $\to$ 主控 | 围栏越界/异常导致受惊 | `alertType`, `description`, `timestamp` | 触发 Rive 受惊与强控报警 |
| `CMD_RESET_HOST_PIN` | 36 | 主控 $\to$ 受控 | 远程重置受控端 PIN 码 | `newPinHash`, `timestamp`, `authSignature` | 需 HMAC 签名与 $\pm 60$s 时间窗 |
| `PET_BAG_SYNC` | 40 | 受控 $\to$ 主控 | 背包全量同步 | `activePetId`, `coin`, `petList[]`, `timestamp` | 首次连接/重连后全量同步 |
| `PET_SWITCH_ACTIVE` | 41 | 受控 $\to$ 主控 | 切换当前活跃宠物 | `petId`, `timestamp` | 切换前先结算旧宠物时间衰减 |
| `PET_SHOP_BUY` | 42 | 受控 $\to$ 主控 | 商店购买回执同步 | `itemId`, `itemType`, `success`, `newCoin`, `timestamp` | 消耗金币解锁道具/物种 |
| `PET_BAG_INTERACT` | 43 | 受控 $\to$ 主控 | 背包宠物串门互动事件 | `targetPetId`, `triggerTs` | 活跃宠物与休眠宠物互动 |
| `PET_REMOTE_GIFT` | 44 | 主控 $\to$ 受控 | 主控远程赠送道具 | `itemId`, `count`, `triggerTs` | 主控下发高级道具 |
| `REMOTE_TASK_SEND` | 45 | 主控 $\to$ 受控 | 主控下发语音文本任务 | `taskId`, `content`, `timestamp` | 内容限 120 字以内 |
| `REMOTE_TASK_ACK` | 46 | 受控 $\to$ 主控 | 任务接收与播放状态回执 | `taskId`, `status(RECEIVED/PLAYED)`, `timestamp` | 任务接收与点击播放双状态回执 |

---

## 三、 安全、鉴权与高危防御体系

### 1. 高危指令鉴权与防重放 (`CMD_RESET_HOST_PIN`)
- **签名算法**：采用 `HMAC-SHA256`。
  $$\text{authSignature} = \text{HMAC-SHA256}(\text{pairingKey}, \text{newPinHash} + ":" + \text{timestamp})$$
- **防重放时间窗口**：受控端比对 $| \text{currentTime} - \text{timestamp} | \le 60\text{s}$，超出时间窗口直接丢弃。
- **校验与重置逻辑**：
  - 校验失败直接丢弃，不返回详细报错，避免侧信道探测。
  - 重置成功后立即清空内存已解锁标记，强制下次进入重新输入新 PIN。

### 2. PIN 防暴力破解机制
- **安全存储**：`EncryptedSharedPreferences` 仅保存加盐哈希值。
- **锁定持久化**：输错 5 次将 `lockedUntilTimestamp = System.currentTimeMillis() + 120_000L` 写入加密存储。
- **抗杀进程**：每次输入前校验 `lockedUntilTimestamp`，未到期直接拒绝，重启 App 无法重置锁定时间。

### 3. 报文零信任参数校验
- 互动参数 `count` 强制约束在 `[1, 5]`，超限截断为 5。
- 四维数值增量强制执行 `coerceIn(0, 100)` 保护。
- 远程任务文本限制 120 字符，过滤特殊控制字符。

---

## 四、 核心业务机制与边界防御

### 1. 心跳保活与超时判定
- **频率与阈值**：受控端每 30s 发送一次 `PING(10)`；任一方超 45s 未收到对应应答，判定对端离线。
- **UI 呈现与恢复**：通过 `SharedFlow` 广播连接状态，界面显示在线/离线标识。网络恢复后自动触发重连并全量同步 `PET_BAG_SYNC(40)`。

### 2. 状态机与休眠宠物切换
- **时间增量衰减**：`onResume` 与收到网络指令时按时间差执行单次扣减，立即落盘更新。
- **物种切换边界**：切换 `activePetId` 前，先对当前活跃宠物执行一次时间差衰减计算并落盘，随后冻结其时间戳；再激活新宠物并刷新其 `lastUpdateTs`，防止数值跳跃。
- **冷启动初始生成**：`pet_bag.json` 不存在时，自动生成初代宠物（`cat`, `level: 1`）。

### 3. 任务队列与离线事件管理
- **远程任务队列 (`task_list.json`)**：
  - 上限 20 条。
  - 达到上限时，优先淘汰已播放（`isPlayed == true`）且时间最早的历史任务；全部未播放时按 FIFO 淘汰最旧任务。
- **离线事件队列**：
  - 上限 50 条。
  - 断网期间本地操作缓存至队列；满 50 条时按 FIFO 丢弃最老记录。
  - 联网后按时间戳正序增量提交主控端，数值类做累加合并。

### 4. 音频通道、焦点与后台 TTS 场景
- **通道分配**：
  - 宠物音效 + TTS：绑定 `AudioManager.STREAM_MUSIC`。
  - 告警强控：绑定 `AudioManager.STREAM_ALARM`。
- **音频焦点 (`AudioFocusManager`)**：系统来电或闹钟介入时，暂停 TTS 与普通音效，通话结束后恢复。
- **告警抢占与震动兜底**：
  - 告警触发时，Service 优先调用 `LocalTtsManager.stop()` 强行打断语音，立即拉起 `STREAM_ALARM`。
  - 同步启动持续脉冲震动，兜底部分 ROM 限制后台 Alarm 音量的情况。
- **后台任务播报**：
  - 受控端处于后台收到 `REMOTE_TASK_SEND` 时，弹出带 Action 的系统通知。
  - 用户点击 Action 直接由 Service 执行 TTS 播报。

### 5. 表现层生命周期与异常降级
- **受惊状态行为互斥**：宠物处于 `SCARE` 受惊状态时，`PetAiEngine` 挂起所有 Idle 随机小动作。
- **生命周期保护**：`PetMainActivity.onPause()` 暂停 Rive 渲染循环、注销 `LookAt` 监听、暂停 AI 决策计时器、停止 TTS 朗读；`onDestroy()` 彻底释放 TTS 引擎。
- **特效可见性**：`KonfettiView` 仅在前台可见状态（`isResumed`）触发，后台静默忽略。
- **硬件降级**：无 GPS/弱信号时寻宝功能静默禁用；无中文 TTS 引擎时自动隐藏朗读热区；`.riv` 解析失败自动降级为原生 Canvas 占位几何图形。

---

## 五、 分阶段开发实施计划

### 阶段一：高质量 MVP 核心
- [ ] **构建配置与环境**：
  - `app-host/build.gradle.kts` 添加 `app.rive:rive-android:8.1.0`、`nl.dionsegijn:konfetti-xml:2.0.4`、`androidx.dynamicanimation:dynamicanimation:1.1.0`。
  - `app-host/build.gradle.kts` 显式配置 `ndk.abiFilters`（`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`）。
  - `InkHostApplication.onCreate` 增加 `Rive.init(this)` 全局单次初始化。
- [ ] **基础协议与模型**：
  - 在 `common` 模块 `MessageType.kt` 扩充 `10~12`、`30~36`、`40~46` 常量。
  - 实现 `PetBag`、`PetItem`、`PetInteractPayload`、`ErrorResponsePayload` 数据模型。
- [ ] **受控端状态机与安全体系**：
  - 实现 `PetStateManager`：时间增量衰减、`pet_bag.json` 落盘读写、冷启动初始生成。
  - 实现 `PinSecurityManager`：`EncryptedSharedPreferences` 哈希存储、长按 3s 判定、5 次输错锁定 120s（重启保持锁定）。
  - 实现 `CMD_RESET_HOST_PIN` 的 HMAC-SHA256 签名校验与 $\pm 60$s 时间戳防重放。
- [ ] **Rive 表现层与音效**：
  - 导入开源宠物 `.riv` 资源与 Kenney OGG 音效。
  - 实现 `RiveLookAtHelper`：屏幕绝对坐标转 Artboard 局部相对坐标与 `onPause` 解绑。
  - 实现 `SoundEffectManager`：`SoundPool` 单例，`STREAM_MUSIC`，0.95~1.05 随机音调。
  - 构建 `PetMainActivity`：磨砂状态 HUD、居中 Rive 精灵、底部互动坞。
  - 实现 `PetAiEngine`：空闲 15s 随机动作，受惊状态抑制 Idle 行为。
  - 接入 `HapticUtil`：实现 `tap()`、`heavyClick()`、`alarmImpact()`。
- [ ] **Service 通信与双向闭环**：
  - 实现 30s `PING/PONG` 心跳保活与 45s 超时判定机制。
  - `InkForegroundService` 与 UI 建立 `MutableSharedFlow` 事件总线。
  - 联调 `PET_INTERACT_CMD(31)` 与 `PET_INTERACT_ACK(32)`（受控端校验 `count <= 5`）。
  - 联调 `PET_ALERT_EVENT(35)`：围栏越界触发受惊抖动，后台 Service 播放 `STREAM_ALARM` 警报与震动兜底。
- [ ] **主控端集成**：
  - `item_device_card.xml` 内嵌宠物状态简报徽章。
  - 主控端实现长按投喂合并防抖（单条携带 `count` 发送）。

### 阶段二：单机小游戏 + 本地儿童 TTS 朗读 + 主控详情面板
- [ ] **受控端 3 款内置小游戏**：
  - 实现石头剪刀布、4x4 记忆翻牌、幸运转盘纯逻辑代码。
  - 接入每日防刷机制：本地持久化每日局数计数器，仅前 5 局派发奖励，跨自然日重置。
  - 获胜触发 `KonfettiView` 全屏彩带特效（前台可见性校验）与胜利音效。
- [ ] **儿童辅助 TTS 本地朗读**：
  - 实现 `LocalTtsManager`：异步队列暂存、中文引擎缺失降级、`onDestroy` 释放。
  - 接入 `AudioFocusManager`：来电/闹钟自动暂停 TTS 与互动音效。
  - `PetMainActivity` 各功能按钮增加透明点击热区，点击触发说明朗读。
- [ ] **低频促活通知**：
  - 接入单次不精确 `AlarmManager`（`setAndAllowWhileIdle`）做饥饿/无聊单次唤醒检测，禁止常驻循环闹钟。
- [ ] **主控端详情页**：
  - 新建 `PetDetailActivity`：展示完整四维状态、经验等级曲线与历史互动日志。

### 阶段三：远程语音任务 + 远程对战 + GPS 寻宝 + 断网增量同步
- [ ] **远程语音文字任务系统**：
  - 主控端新增【发布任务】面板，通过 `REMOTE_TASK_SEND(45)` 下发文本。
  - 受控端 `InkForegroundService` 落盘保存至 `task_list.json`（优先淘汰已播放任务），回复 `REMOTE_TASK_ACK(46)`。
  - 前台弹窗询问播放 / 后台通知栏 Action 按钮一键播放。
  - 受控端侧边栏新增「任务记录」列表，支持点击条目重播语音。
  - 告警触发时，优先强行停止 TTS 语音播放。
- [ ] **Ably 双向远程猜拳**：
  - 实现 `PET_GAME_INVITE(33)` 邀请流程与 `PET_GAME_ACTION(34)` 揭晓结算。
- [ ] **GPS 移动寻宝**：
  - 80 米位移阈值与 60 秒冷却，换算真实移动为宠物探索奖励与金币；无 GPS 模块静默降级。
- [ ] **离线增量合并**：
  - 受控端维护上限 50 条的离线事件队列，联网后增量同步主控端并执行合并。

### 阶段四：宠物商店 + 背包多宠物切换 + 串门互动
- [ ] **多宠物背包与商店**：
  - 数据模型全量切换为 `PetBag`；实现旧版本单宠物平滑迁移。
  - 切换 `activePetId` 前执行旧宠物时间差衰减落盘；Rive 动态切换 Artboard。
  - 商店内置商品表：新物种、皮肤、高级消耗道具，金币购买校验。
- [ ] **宠物间串门互动**：
  - 本地主宠物与背包休眠宠物玩耍对话气泡。
  - 主控端查看受控端完整背包，下发 `PET_REMOTE_GIFT(44)` 赠送高级道具。

### 阶段五：成长时刻与看护增强（零新增协议码位）
- [x] **宠物升级时刻回传**：
  - 受控端：`PetMainActivity.checkLevelUpMoment` 对比上次展示等级，上升时触发全屏 Konfetti + 音效 + TTS 恭喜。
  - 主控端：`PetDetailActivity.notifyLevelUpIfRaised` 对比上一快照 level，上升时弹「宠物升到 Lv.X」庆祝提示。
- [x] **儿童离线/超时提醒（主控端本地逻辑，`CareMonitor`）**：
  - 受控端 PING(50)/HEARTBEAT 喂活跃时间戳，45s 无信号判定离线；离线持续超 30 分钟 → 本地通知提醒（每轮离线只提醒一次）。
  - 连续在线超 2 小时 → 本地通知「该让孩子休息了」，提醒后重置会话计时。
  - 纯主控端本地计算，不新增报文；`inklink_care` 通知渠道。
- [x] **任务完成激励闭环**：
  - 主控端收到 `REMOTE_TASK_ACK(46, PLAYED)` 时弹确认框「任务已完成，发放奖励？」，确认后一键走 `PET_REMOTE_GIFT(44, itemId=coin_10)` 发放 10 金币。
  - 受控端 `onPetRemoteGift` 支持 `coin_<额度>` 奖励类目直接入账金币。
  - 形成「发任务 → 完成 → 奖励」完整激励循环，协议复用 45/46/44。

### 阶段六：好友社交与受控端互玩（仅 Ably 模式，协议零新增码位）
> 局域网 `LocalWsTransport` 保持 1对1 不变；主控端 App 不参与好友体系。

- [x] **好友模型（对称订阅，无握手协议）**：
  - pairingKey 即好友钥匙：双方各自手动输入对方 pairingKey + 昵称添加好友，各自订阅对方频道 `inklink-pet-<对方pairingKey>`，实现互见。
  - 受控端新增 `FriendRepository`：`Friend(deviceId, nickname, pairingKey, addedTs)` 落盘 `friends.json`；Presence 上线时回填真实 deviceId。
  - `AblyRelayTransport` 扩展：`attachChannel/detachChannel` 挂载好友频道；`targetChannelResolver` 按目标设备路由发布频道；Presence 回调上报好友上下线。
- [x] **在线设备显示**：
  - Ably Presence 获取好友在线/离线状态（presence PRESENT/ENTER/LEAVE + 2s 轮询刷新徽章）。
  - 受控端新增 `FriendActivity`：好友列表 + 在线徽章 + 添加/删除好友 + 「对战」「串门」入口；PetMainActivity 互动坞新增「好友」按钮。
- [x] **受控端互玩 — 只游戏、不控制（transport 层白名单强制）**：
  - 好友频道进站消息仅放行 `PET_GAME_INVITE(33)`、`PET_GAME_ACTION(34)`、`PET_BAG_INTERACT(43)`（`FriendPolicy` 定义于 common，白名单过滤在 `AblyRelayTransport` 接收侧执行）。
  - 管理类指令（31/36/44/45 等）仅接受已配对主控端频道来源，好友频道直接丢弃。
  - 状态隐私报文（30/35/40）永不发往好友频道。
- [x] **跨设备猜拳对战**：
  - 发起方受控端先出招并记录 `GameInviteSession`（inviteId -> myChoice），`InkForegroundService.onPetGameAction` 收到 34 后本地判定胜负 Toast + 弹窗展示。
  - 应答方复用现有 `PET_GAME_INVITE` 弹窗出招链路；33 加入 `IdempotentController` 防重复弹窗。
- [x] **跨设备串门互动（43 开放给好友）**：
  - 好友间互发 `PET_BAG_INTERACT`：被访端弹「XX 来串门啦」TTS/气泡 + 活跃宠物心情 +3 + 爱心特效。
  - 主控端收到 43 仅做提示展示，不阻断。
  - 附带补齐阶段四本地串门缺口：背包弹窗「串门」按钮，活跃宠物与随机休眠宠物玩耍（心情 +3）并上报主控端 43。

### 阶段七：宠物养成体验深化（生命周期 / 多动作 / 健康系统 / 动画与卡片 UI，协议零新增码位）
> 对齐用户《宠物养成 UI 设计规范》与开源参考（GooseDroid / hogotchi / TamagotchiP2 / Linguin）。

- [x] **生命周期系统**：
  - `PetItem` 扩展 `name / health / birthTs / lifeStage / isSleeping / isAlive / interactions`；`PetBag` 扩展 `pillCount / schemaVer`。
  - `PetStateManager.migrate()`：Gson 绕过 Kotlin 默认值导致旧档缺字段落成 false/0，按 `schemaVer<1` 统一修补，避免老宠物被误判"已衰弱"。
  - 四段式生命周期：`EGG`（商店新购，互动 3 次破壳）→ `CUB`(Lv1, 0.65x 体型) → `JUVENILE`(Lv2-3, 0.85x) → `ADULT`(Lv4+, 1.0x)。
- [x] **六类互动动作（31 指令 action 扩展，零新增码位）**：
  - 喂食 `FEED`（+饱食+心情）/ 玩耍 `PLAY`（+心情、-精力、+exp）/ 学习 `LEARN`（-精力-心情、+大量 exp）/ 清洁 `CLEAN`（+清洁）/ 睡觉 `SLEEP`（isSleeping 开关，睡眠时精力恢复、饱食衰减减半）/ 治疗 `HEAL`（消耗药丸，恢复健康、唤醒衰弱宠物）。
  - 受控端 `onPetInteractCommand` 按 action 分发执行 + 回执 `PET_INTERACT_ACK(32)` 新增 `note` 字段承载失败原因/执行说明（旧端忽略兼容）。
  - 主控端 PetDetailActivity 新增「远程日常照料」按钮组；32 回执展示执行结果与宠物快照。
  - **修复历史双重结算 bug**：原 `FeedRemote` 事件在 Service 与 UI 各 `feed()` 一次；现 Service 唯一结算，UI 事件仅播动画。
- [x] **健康与生病系统**：
  - 清醒时 `hunger<20 || clean<20` 持续 → 每 10 分钟 `health -1`；`health<=0` → `isAlive=false` 进入虚弱沉睡（非死亡，可治疗唤醒，儿童友好）。
  - `PetMoodMapper` 新增 `WEAK`（虚弱：乌云+下垂眼，点击气泡直达治疗）与 `SICK`（X 形眩晕眼+冷汗+苍白脸色）优先级最高。
  - 药丸：商店购买（库存上限 5，初始赠送 1 颗），虚弱/生病气泡点击一键治疗。
- [x] **状态机架构收敛**：`PetStateManager` 改为进程级共享（companion 持有 `cachedBag` + 同一 `pet_bag.json`），Service/UI/Receiver 多实例不再互相覆盖；`persist()` 保证直改字段（coin/pillCount）落盘；`addReward()` 统一奖励入账并触发升级。
- [x] **多物种 Canvas 渲染升级**：6 物种（猫/狗/兔/企鹅/仓鼠/熊猫）独立配色+耳型+细节，蛋形态（斑点+裂纹）、阶段体型缩放、SLEEPY 闭眼+Zzz、SICK/WEAK 病容、睡呼吸吸降速、虚弱/苍白颜色混合。
- [x] **UI 布局对齐规范**：
  - 顶部 HUD：名字 + Lv/生命周期徽章 + 金币徽章 + 四维压缩进度条（饱食/心情/精力/清洁，`setProgressCompat` 平滑过渡）。
  - 宠物区占屏 ~45%（视觉重心）；状态提示气泡（异常态显示，异常可直达治疗）。
  - 底部双行操作坞：互动行（喂食/玩耍/学习/清洁/睡觉，emoji 胶囊 Tonal 按钮）+ 功能行（背包/商店/任务/游戏/好友/聊天/管控）。
- [x] **动画与反馈（规范第五章）**：
  - 点击宠物回弹（0.85→1.05→1.0 弹簧三段 + 抚摸结算）。
  - 道具图标飞入（喂食/清洁/学习/睡眠/治疗 emoji 从操作坞飞向宠物 + 缩放淡出）。
  - 数值/等级/金币飘字上浮淡出；星星粒子（玩耍/奖励）、水泡粒子（清洁）、礼花（升级/串门/孵化）。
  - 升级「Lv.X ⬆」庆祝 + TTS；生命周期进阶「成长 🌱少年」提示；破壳「🐣 破壳啦」全屏庆祝。
- [x] **弹窗卡片化（BottomSheet，参考 Linguin 双列网格）**：
  - 商店：网格商品卡（emoji 图标/名称/价格/购买按钮，含 5 种宠物蛋+药丸，购买后刷新）。
  - 背包：网格宠物卡（阶段/等级/四维摘要/切换出战）+ 串门入口。
  - 任务：任务卡片（内容/新/已播状态徽章/播放按钮）。
- [x] **多宠物选择体系**：商店新增 5 物种蛋（狗 100/兔 150/企鹅 200/仓鼠 250/熊猫 300），背包卡片切换出战，物种中文名与 emoji 全链路（HUD/背包/商店/提示/主控端）。

---


## 六、 高危安全防御与异常自检清单

- [ ] **网络零信任校验**：受控端对所有来自 Ably 网络的消息进行参数范围校验（`count <= 5`、属性增量 `coerceIn`）。
- [ ] **高危指令强鉴权**：`CMD_RESET_HOST_PIN` 必须校验 HMAC-SHA256 签名，非法签名直接丢弃不报错。
- [ ] **防重放攻击保护**：`CMD_RESET_HOST_PIN` 校验时间戳窗口 $\pm 60$s，超出直接丢弃。
- [ ] **PIN 锁定抗杀进程**：暴力破解锁定截止时间戳保存在 `EncryptedSharedPreferences`，重启 App 无法重置锁定。
- [ ] **敏感数据无明文**：PIN 码仅保存加盐哈希，内存与磁盘全程不留明文。
- [ ] **离线队列上限防护**：离线事件队列设置 50 条上限，超出按 FIFO 丢弃最老记录，防止存储膨胀。
- [ ] **文本输入转义**：远程任务内容做特殊字符过滤与长度截断，防止 TTS 引擎解析异常。
- [ ] **休眠宠物数值防跳跃**：活跃宠物切换前必须先完成当前宠物时间差衰减计算。
- [ ] **音频焦点与告警抢占**：告警铃声触发第一行必须调用 `ttsManager.stop()`；告警同时触发震动兜底。
- [ ] **Activity 泄漏防御**：`LocalTtsManager`、`SoundEffectManager`、Flow 收集在 `onDestroy` 彻底解绑与释放。

---

## 七、 运行时工程实现备注

1. **消息幂等去重防护**：
   - 复用组件 `IdempotentController`，针对 `PET_INTERACT_CMD(31)`、`PET_REMOTE_GIFT(44)`、`REMOTE_TASK_SEND(45)`，使用对应 `msgId` / `taskId` 做 60 秒内存滑动窗口去重，规避网络抖动导致重复投喂、道具重复发放、任务重复弹窗。
2. **ROM 后台保活兼容处理**：
   - `InkForegroundService` 启动模式声明 `START_STICKY`，服务被系统杀死后尝试自动重启。
   - 管控设置页 `HostSettingsActivity` 增加引导入口：跳转系统意图 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，引导用户授予忽略电池优化权限。

---

## 八、 公网传输层：Ably Transport

> **适用场景**：个人自用编译，APK 不对外分发；API Key 直接内置代码；禁止对外发布 APK。

绝大多数业务可以直接使用 Ably 替代自建甲骨文 WebSocket 中转服务器，无需维护 Node.js 服务。

### 1. 核心约束与架构变化
- **重要约束**：本项目为个人自用，APK 不分享、不对外发布，因此允许将 Ably API-Key 直接编译进客户端代码；一旦 APK 流出，密钥会被反编译泄露，消耗账号额度。
- **架构变化**：移除自建 Ubuntu-WS 中转；受控端、主控端两端 Kotlin SDK 直连 Ably Pub/Sub Channel。
- **业务解耦**：业务逻辑、鉴权、状态机、本地存储全部保留在安卓两端；`MessageType 10-46` 消息协议定义完全不变。

### 2. Ably 原生直接支持能力
1. **双向实时消息、二进制载荷**：JSON、二进制载荷均支持，适配整套 `MessageType` 消息；单条消息最大 64KB，GPS、宠物状态、指令报文均满足。
2. **Presence 在线状态**：用于网络层感知 clientId 上下线，网络抖动自动重连恢复。业务层保留原有 PING-PONG 心跳做兜底校验，Presence 仅作为网络层参考，不替代业务心跳。
3. **断线重连、消息历史回补**：短时间断网重连自动补发消息；长时间离线超过 72 小时历史消息丢失，触发 `PET_BAG_SYNC` 全量状态同步。
4. **消息排序、Ably 幂等发布**：发布时携带 `msgId` 开启服务端幂等；客户端 `IdempotentController` 60 秒内存滑窗去重必须保留，防止网络抖动产生重复消息。
5. **传输协议自动降级**：WebSocket / SSE / HTTP 长轮询自动切换，对老旧安卓设备网络兼容性优于自建 WebSocket。
6. **全球边缘节点**：无需甲骨文 OCI 服务器，免除抢实例、安全组、ufw、服务器宕机运维。
7. **Android Kotlin SDK**：实现 `Transport` 抽象接口，上层业务代码无改动。

### 3. 必须客户端实现，Ably 不提供的逻辑（全部保留现有代码）
1. **一对一设备寻址**：Ably 为广播 Channel 模型，配对设备使用私有 Channel：`inklink-pet-${pairingKey}`。受控端、主控端订阅同一个私有 Channel 实现 1 对 1 通信，依靠 `pairingKey` 隔离不同配对设备，避免消息串扰。
2. **高危指令 `CMD_RESET_HOST_PIN`**：HMAC-SHA256 签名、$\pm 60$s 时间窗口防重放完全由安卓两端校验，Ably 仅透传字节流，不做业务鉴权。
3. **本地存储逻辑不变**：离线事件队列（上限 50 条 FIFO 淘汰）、`task_list.json` 任务队列淘汰逻辑全部在安卓本地。
4. **报文零信任校验**：`count.coerceIn(1, 5)`、数值边界保护，受控端本地执行。
5. **PIN 加密存储、宠物状态机、Rive 渲染、TTS 音频、GPS 采集、电子围栏逻辑完全不变**。

### 4. 双传输通路并存
- **局域网模式**：沿用 `LocalWsTransport`，受控端本地 WebSocket Server，不走 Ably。
- **公网模式**：使用 `AblyTransport`。
- 上层依赖 `Transport` 抽象接口，业务层无感切换；设置页增加开关：`使用 Ably 公网通道`。

### 5. 功能兼容对照表

| 模块 | Ably 支持 | 备注 |
| :--- | :---: | :--- |
| PING-PONG 心跳保活 | ✅ | Presence 做网络参考，业务心跳保留兜底 |
| 整套 MessageType 10-46 消息 | ✅ | 协议完全不变，报文原样透传 |
| 消息幂等防重复 | ✅ | Ably msgId 幂等 + 客户端 IdempotentController 双保险 |
| 离线事件队列 | ✅ | 安卓本地持久化，Ably 负责联网投递 |
| 远程投喂、道具赠送、远程任务 | ✅ | 私有 Channel 发布消息 |
| 远程猜拳对战 PET_GAME_* | ✅ | 双向收发消息 |
| GPS 上报、越界告警 PET_ALERT_EVENT | ✅ | JSON/二进制透传 |
| 高危指令 HMAC 签名防重放 | ✅ | 两端 Kotlin 自行校验 |
| 背包全量同步 PET_BAG_SYNC | ✅ | 72 小时以上离线强制全量同步 |

### 6. Ably 免费版限制
1. 消息额度、并发连接存在上限，个人原型够用；多设备大规模使用会超限。
2. 消息历史仅保存 72h，超长离线消息丢失，触发全量背包同步。
3. 单消息最大 64KB，禁止传输大体积二进制数据。

### 7. 常量定义（Kotlin，个人自用）
```kotlin
// 仅个人本地编译使用，APK 严禁分享出去！
object AblyConfig {
    const val ABLY_API_KEY = "your-ably-api-key"
    const val CHANNEL_PREFIX = "inklink-pet-"
}
```

---

## 九、 游戏化终稿规范 V1.1（阶段八 / 九 / 十 实施基准）

> **一句话**：把"数值面板 + 按钮"升级成"有自己意志的像素电子宠物"。底层通信与阶段七已验证的状态机骨架保留，新增双时钟数值、部件化像素渲染、AI 行为树、性格、事件日志、随机事件、里程碑/每日目标、像素场景、三类跨设备礼物。
> **本节与 1-8 章冲突处（如"禁止 emoji 图标"、旧 UI 规范静态图渲染、Room 缺失）一律以本节为准。**

### 9.1 七项裁决（终稿）

| # | 议题 | 裁决 |
| :--- | :--- | :--- |
| 1 | 数值速率 | **双时钟**：前台"佛系"衰减（满值掉光 16/23/20/32 小时）；后台/灭屏 **自动休眠豁免**（四维不掉，精力/健康恢复）；睡觉同规则 |
| 2 | 远程 Buff 增益 | 赠送 buff 附带 **10 分钟对应属性衰减暂停**，避免秒级衰减抵消 |
| 3 | 公网中转 | **删除全部"Oracle/Ubuntu 自建中转"描述**，统一 **Ably 公网 + 局域网 WebSocket 双通路** |
| 4 | 动画定义 | **部件关键姿态（2-4 关键帧）+ 程序化补间**为主方案（MAIN）；序列帧 PNG 仅后期可选 manifest 插槽；**不用 AnimationDrawable**；Rive 依赖保留、后置评估 |
| 5 | 物种差异 | 共骨架 + **三件套**：头饰贴片 / 背饰贴片 / 尾饰贴片 + **LUT 调色表**；成年 4 形态 = 同骨架换装饰层（无/头巾/眼镜/压暗萎靡） |
| 6 | 健康系统 | 完整写入数值节：`hunger<20 ∥ clean<20` 侵蚀；睡眠恢复；`health=0` → 虚弱沉睡（可治疗唤醒，**无死亡**） |
| 7 | 赠送与收尾 | 仅 **主控端→受控端**可赠礼（好友互访/逗弄不开放实物互赠）；补金币经济再平衡；`pillCount → itemStock["potion_heal"]` 迁移；新增宠物改名入口；工程纪律：**10-30s 节流写库** + **事件日志环形 500 条** |

### 9.2 美术渲染硬性规范（MAIN：程序化像素）

- **部件画布** 96×96px，代码 **整数倍放大**（×5=480 / ×6=576）；硬像素边缘；调色板 ≤24 色；全链路 ARGB。
- 资源目录 `drawable-nodpi/`；`Bitmap.isFilterBitmap=false`、`isAntiAlias=false`、`inScaled=false`（当前无 PNG 资产，由程序化绘制，插槽预留）。
- **锁竖屏 9:16**（`PetMainActivity` 已 portrait）。
- **废弃原生 Toast → 自定义像素气泡**；**废弃 Material 进度条 → 分段像素胶囊**（低值红色闪烁）。
- UI = 像素**视觉语言**（图标/气泡/进度条/背景），**不使用像素中文字体**（保持系统无衬线，防中文发虚）。

### 9.3 数值规则（双时钟）

**前台活跃**（`App` 可见）全速：满值掉光时长为 饥饿 **16h**、开心 **23h**、精力 **20h**、清洁 **32h**（即每点 -1/576s、-1/828s、-1/720s、-1/1152s），经验/成长独立。（2026-09-05 用户裁定"最佛系"节奏：一次喂饱管一整天，孩子放学/睡前各喂一次即可，不再"救火"。）
**后台/灭屏**：自动休眠语义（2026-08-31 修订，替代原 ÷20+地板 30）——四维完全豁免不衰减，精力/健康按睡眠速率缓慢恢复，即"不玩就不掉状态"。
**睡觉**：遵循同一双时钟；前台睡眠 精力 +1/10s、健康 +1/30s；后台 ÷20。

`health` 健康：
- 触发侵蚀：`hunger<20 || clean<20` → 前台 -1/90s、后台 ÷20；
- 睡眠恢复：前台 +1/30s；
- `health<=0` → `isAlive=false` 虚弱沉睡（阻断互动，仅 `potion_heal`/`buff`/治疗唤醒）；**无死亡、无删除**。

所有属性 `coerceIn(0,100)`。离屏结算仍基于 `lastUpdateTs` 时间差幂等（沿用阶段七机制，仅换速率函数为双时钟）。

**本地背包道具（一次性消耗，进 `itemStock`）**（2026-08-30 降价修订：全部约 ×0.5）

| itemId | 价格 | 效果 |
| :--- | :--- | :--- |
| `food_normal` | 5 | 饥饿 +25 |
| `food_premium` | 15 | 饥饿 +50，开心 +10 |
| `toy` | 10 | 触发玩耍：开心 +30，精力 -15 |
| `book` | 10 | 学习：经验 +35，精力 -20，开心 -8 |
| `shower_gel` | 8 | 清洁 +45 |
| `sleep_potion` | 12 | 精力 +60 |
| `potion_heal` | 25 | health 恢复满（治疗/唤醒） |

**蛋/物种价格（降价修订）**：dog 100→50、rabbit 120→60、penguin 150→75、hamster 180→90、panda 220→110、fox 260→130、dragon 320→160、sheep 360→180、hedgehog 400→200；**装饰**：头巾 80→40、眼镜 80→40、蝴蝶结 100→50、皇冠 150→75。
**金币奖励（上调修订）**：每日目标 30→60、随机事件金币 5-15→10-30、转盘保底/中/高/大奖 5/15/30/100 → 10/30/60/200、每日防刷 5→8 局。

**远程 Buff 增益（主控→受控，不进背包，即时生效 + 10min 该属性暂停衰减）**

| buffId | 效果 |
| :--- | :--- |
| `buff_energy` | 精力 +40，精力衰减暂停 10 分钟 |
| `buff_mood` | 开心 +30，开心衰减暂停 10 分钟 |

### 9.4 部件化宠物架构

```
Rig（共骨架，程序化像素）
 ├ 身体 body      ├ 头 head(可旋转)   ├ 左手/右手/左脚/右脚(可旋转摆动)
 ├ 眼睛 eyes(睁/闭/表情切换)          ├ 嘴巴 mouth(表情切换)
 └ 叠加插槽: 头饰槽 headSlot / 背饰槽 backSlot / 尾饰槽 tailSlot
每部件属性: position(Vector2) · rotation · scale · alpha —— 代码姿态补间驱动
动作 = 一组关键姿态 + 缓动(Easings)：idle呼吸/blink/伸懒腰/打哈欠/蹦跳/翻书/甩水/受惊...
饥饿·脏乱状态：不做专属身体动画，用 头顶符号 + 情绪气泡台词 + LUT 压暗 表现
```

**10 物种**（共骨架 + 三件套贴片 + LUT 调色表）：

```kotlin
enum class PetSpecies { CAT, DOG, RABBIT, PENGUIN, HAMSTER, PANDA, FOX, DRAGON, SHEEP, HEDGEHOG }
```

**生命周期**：蛋 `EGG` → 幼年 `CUB` → 少年 `JUVENILE` → 青年 `ADOLESCENT` → 成年 `ADULT`；
成年瞬间由 **照料质量 + 性格** 共同定型 4 形态 `finalForm`：`BALANCED 均衡` / `PLAYFUL 活泼头巾` / `STUDIOUS 学眼镜` / `DROOPY 萎靡压暗`。

### 9.5 AI 行为树（PetAiEngine 升级）

- **IDLE 自主行为**：8-20s 随机触发（blink / 左右张望 / 伸懒腰 / 发呆 / 打哈欠），全部部件姿态；
- **状态抱怨**：低数值 → 气泡台词（饿"肚子饿啦" / 困"好想睡觉" / 脏"身上脏脏的"）+ 揉肚/揉眼/挠身姿态；
- **性格双特质**（写入 `PetItem`，随 30/40 快照双端同步）：`playfulness[-100,100]`、`affection[-100,100]`；
  常陪玩耍↑爱玩（空闲更爱蹦跳、主动撒娇）；常抚摸↑亲密（点击反应更欢快）；长期冷落↓爱玩（消沉、小动作变少）；
- **触摸三手势**：单击=转头看向点击处+缩放回弹；长按抚摸=抚摸姿态+亲密↑心情↑；快速连点(≥8次/2s)=摇头烦躁+心情↓（第一次仅警告气泡）。

### 9.6 游戏化系统

- **随机事件 + 事件日志**：正向（捡到道具）/ 负向（变脏、小病），全部写入 `EventLog`（时间戳+描述），📜页可看宠物一生；**Room 持久化，环形上限 500 条自动裁剪**。
- **里程碑 🏆**：孵化、首次成年、解锁像素房间背景、集齐形态 → 发放奖励；
- **每日目标**：喂食×2、玩耍×1、清洁×1 → 完成领金币；
- **像素场景**：默认卧室 / 温馨客厅 / 阳光窗台，**等级解锁**，背包-场景切换；
- **经济再平衡**：删除 3 款本地小游戏（保留远程猜拳/好友对战=社交）；金币来源改为 每日目标 / 里程碑 / 随机事件 / GPS 寻宝 / 家长赠送。

### 9.7 跨设备社交与三类礼物（复用现有码位，零新增）

| 行为 | 通道 | 说明 |
| :--- | :--- | :--- |
| 串门拜访 | 43 `PET_BAG_INTERACT` | 访客宠物短暂登场碰头动画，双方开心↑ |
| 互相逗弄 | 43 JSON `event=pet_tease` | 被逗端播放反馈动画 |
| 赠送礼物 | 44 `PET_REMOTE_GIFT` | 三类：`item`/`deco`/`buff`，**仅传 ID 字符串** |

**礼物 payload 扩展**（`PetRemoteGiftPayload` 加 `giftType` 字段，Gson 向后兼容）：

```json
{"giftType":"item|deco|buff","itemId":"sleep_potion|deco_bandana|buff_energy","count":1}
```
受控端 `onPetRemoteGift` 分支：`item`→`itemStock +=`；`deco`→解锁 `unlockedDecorations`；`buff`→即时改属性+`pauseDecayUntilTs`（10min）；播 `receive_gift` 部件动画 + 8bit 音效 + 像素气泡。
**FriendPolicy 不变**：44 不在好友白名单，好友之间无法互赠（transport 层强制）。

### 9.8 Room 持久化与同步

- `app-host` 引入 **Room**（`room-runtime`/`room-ktx` + **KSP** `room-compiler`）。
- 实体：`PetEntity`（含 `playfulness/affection/finalForm/itemStock(JSON)/unlockedDecorations`）、`EventLogEntity`（环形500）。
- **一次性导入**：首启若 `pet_bag.json` 存在且 Room 空，导入并迁移 `pillCount → itemStock["potion_heal"]`；旧 JSON 转为备份。
- **写节流**：秒级内存运算，**10-30s 落地一次**（`lastPersistTs` 判定），避免闪存磨损/ANR。
- 同步：30/40 整包 JSON 快照照旧（新增字段自动随之双端透传）；重要指令（44 赠礼）补 **ACK 回执**（受控→主控 `CMD_ACK/PET_INTERACT_ACK` 风格，主控侧幂等）。

### 9.9 音频

复用 `SoundEffectManager`（SoundPool 单例已就位）；补齐 **8-bit 短音效**：进食 / 玩耍欢快 / 翻书 / 流水 / 呼噜 / 生病呻吟 / 金币 / 升级礼花 / 收到礼物。

### 9.10 数据类终稿（新增字段）

```kotlin
data class PetItem(
    ... 阶段七字段保留 ...,
    var playfulness: Int = 0,        // [-100,100]
    var affection: Int = 0,          // [-100,100]
    var finalForm: String = "",      // ""未定 / BALANCED / PLAYFUL / STUDIOUS / DROOPY
    var sceneId: String = "bedroom", // 当前场景
    var lastDecayPauseTs: Long = 0,  // (buff_energy) 暂停到期时间戳；0=无
    var moodPauseUntilTs: Long = 0   // (buff_mood) 暂停到期时间戳
)
data class PetBag(
    ... 阶段七字段保留 ...,
    var itemStock: MutableMap<String, Int> = mutableMapOf(), // 道具库存(含 potion_heal)
    var unlockedDecorations: MutableList<String> = mutableListOf(),
    // pillCount 保留仅用于迁移读取, 迁移后并入 itemStock["potion_heal"], 弃用
)
```

### 9.11 参考仓库要点（已研读，落地映射）

- **GooseDroid**：`BehaviorTree`(Selector/Sequence/RandomSelector + Blackboard)、`GooseRig`(Bone 树 + Expression/Pose 枚举 + squash-stretch + blink 计时 + 姿态 LERP)、`PetPersonality`(trait ±100 + onPet/onPlay/onFeed/onIgnore + 行为乘数)、`PetNeeds`(离线时间差封顶结算)。→ 映射到 `PetAiEngine` 重写、部件 Rig、`PetItem` 性格、双时钟结算。
- **hogotchi**：数值模型/成长公式/里程碑。 **TamagotchiP2**：随机事件/成长分支/事件日志。 **Linguin**：商店背包道具结构 + Room DAO 范式（`PetRepository`/`PetDao`/`PetWorkManager`）。

### 9.12 开发任务（阶段八 / 九 / 十）—— 详见 `tasklist.md`

- **阶段八（P0 内核）**：双时钟衰减控制器 + health 完整逻辑 + buff 暂停计时 + Room 化 + `pillCount→itemStock` 迁移 + 节流写库 + 事件日志环形 500 + 性格字段 + AI 行为树重写 + 触摸三手势 + 单元测试。
- **阶段九（P1 表现）**：`PetSpriteView` 重构为部件 Rig 引擎 + LUT 换色 + 10 物种三件套 + 成年 4 形态装饰 + AI 对接部件姿态 + 像素 UI（分段胶囊/像素气泡/废 Toast/HUD 文案游戏化）+ 改名入口 + 赠送按钮 UI + 8bit 音效接入 + 随机事件/里程碑/每日目标/像素场景落地。
- **阶段十（P2 社交闭环）**：44 三类礼物协议扩展（item/deco/buff + ACK 幂等）+ 受控端分支处理 + 串门/逗弄完善 + 好友禁赠校验 + 经济再平衡 + 双通路联调 + 边界测试（离屏/虚弱/buff 超时/背包满/日志溢出/节流）。
- **后置可选**：Aseprite PNG 部件图集替换程序化渲染（manifest 插槽，业务零改）、Rive 评估接入。

> ⚠️ **像素只是风格，不等于简单**：游戏感来自 多帧姿态 + AI 自主行为 + 情绪气泡台词 + 随机事件 + 社交互动，缺一不可。

---

## 附录A：PNG 美术素材契约（PIXEL_PNG 模式 · 2026-08-31 拍板）

> 本附录是 9.2「插槽预留」的正式启用。渲染三模式共存：`VECTOR_OLD`（矢量卡通）/ `PIXEL_RETRO`（程序化像素）/ `PIXEL_PNG`（PNG 素材，内置自动回退）。业务层、状态机、数据库、协议零改动；仅 `PetCatalog.SPECIES` 静态追加。

### A.1 三条硬性契约

1. **画布契约**：一律 96×96px、透明底（RGBA）。禁止裁剪画布、禁止改画布尺寸，只允许移动图层内像素；角色四周必须留透明边，不许贴画布四边。
2. **锚点契约**：全局唯一锚点 = 画布坐标 **(48,48)**，即角色胸口/视觉重心。所有物种所有帧视觉重心压十字交点。体型大者向内收缩留边；体型小者必须拉回锚点，禁止飘角落。蛇/熊等极端体型同此规则。
3. **像素契约**：缩放必须邻近插值（硬像素，禁双线性/平滑）；输出 PNG-32 RGBA；Alpha 通道仅允许 0 或 255（二值化，禁半透明杂色边）；同物种各帧身体轮廓像素级重合，只允许五官变化。

### A.2 命名与状态集合

- 文件名：`物种code_状态.png`，全小写下划线。code 与 `PetCatalog.SPECIES.code` 严格一致。
- 状态集合（封闭）：`idle / hungry / happy / sleep / idle_a / idle_b / blink`。
  - 核心 4 帧：14 物种全配（`idle/hungry/happy/sleep`）；
  - 动画样板帧（`idle_a/idle_b/blink`）：仅 dog/cat/rabbit 三物种；其余物种检测不到动画帧 → 复用静态 `idle`。
- 存放目录：`app-host/src/main/res/drawable-nodpi/`（禁止 drawable 及各 dpi 桶，防系统 DPI 自动缩放破坏像素坐标）。

### A.3 渲染模式与三级回退链

`PIXEL_PNG` 模式渲染决策（自上而下）：

1. `lifeStage != ADULT`（EGG/CUB/JUVENILE/ADOLESCENT）→ **整只走 PIXEL_RETRO 程序化绘制**（成年切换借进化动效掩盖风格跃迁）；
2. ADULT 且 当前状态有精确 PNG（idle/hungry/happy/sleep）→ **drawBitmap 该帧**；
3. ADULT 且 状态为 sad/annoy/sick（无对应 PNG）→ **idle PNG 本体 + 程序化头顶状态角标叠加**（乌云/怒气/汗滴，UI 叠加层，不属于 offset 补丁）；
4. 物种 idle PNG 缺失（含呼吸/眨眼帧缺失，该级自动跳过）→ **整只回退 PIXEL_RETRO 程序化绘制**。

呼吸眨眼时序沿用程序化参数：idle_a/idle_b 交替 600–800ms；blink 瞬时约 100ms，随机间隔触发；缺帧自动回退静态 idle。

### A.4 Android 侧加载铁律

- `BitmapFactory.Options().apply { inScaled = false }` 解码；禁止 ImageView 显示期缩放，必须自定义 View `drawBitmap`；
- Paint：`isFilterBitmap = false`、`isDither = false`、`isAntiAlias = false`；
- **整数倍放大 + 余数居中**：`s = floor(min(w,h)/96)`（≥1），偏移 `(w-96s)/2`，保证 pixel-perfect 均匀方格；
- 按需解码：仅缓存当前展示物种帧（`LruCache`），禁止启动全量预载；
- Debug 断言：每张解码后 `width == height == 96`，不符即 Log 报错。

### A.5 素材生产流水线（主路：自有 LUT 程序化出图；外部草稿走 `--adapt` 兜底）

1. **素材源**：`tools/pet_sprite/sprite_author.py`（AI 侧，确定性工序）
   - 正则解析 `PixelSpecies.kt` 当**唯一真值源**（14 物种 / 7 色 palette / 头背尾槽位 / 标志位），
     在 24×24 逻辑格上按槽位画造型，×4px 光栅化为 96×96 透明底草稿 → 一次 59 张
     （14 物种 ×4 核心帧 + 猫/狗/兔 ×1 blink）。**风格与 VECTOR_OLD/PIXEL_RETRO 同源**，
     锚点与帧间零位移由「同格起画」构造保证，版权干净；改造型只改设计常量后重跑。
   - 为什么不用外部素材包/文生图：跨包风格分裂、没有 hungry/happy/sleep 状态差分帧；
     且文生图服务实测返回 `insufficient balance`（额度不可靠，不能当交付关键路径）。
   - 外部草稿（AI 生成或手绘）仍支持：投 `tools/pet_sprite/assets_draft/<code>_<state>.png`
     （中间产物，`.gitignore` 不入库），要求品红底 `#FF00FF` 便于色键，并用 `--adapt` 处理。
   - **画师侧三条硬门禁**（都是实测抓出的真 bug，不是臆想）：
     ① `idle/blink` 必须只有 1 个连通域 → 抓到 owl 耳羽簇悬空 11 格、penguin 扇尾末行 1 格；
     ② 道具与身体净空 ≥2 格（身体 outline 还要外扩 1 格）→ 抓到 hedgehog 背刺与 Zzz 合并成同一
     连通域、锚点被道具顶偏 4px；不满足则自动换四角候选位，全失败即报错要求缩小身体；
     ③ 道具限 1..22 安全框，自身 + outline 环不得出画布；
     ④ 五官的 y 必须由**头部中心单点推导**（`my = eye_y + 3.6`），不许各写死一套坐标：蛇的头是
        `draw_body` 画的、中心在 y=8.4，而通用槽位写死 11.4/15，结果五官整张落在盘身上、头部
        变成一块空白青斑；同理腮红等装饰**只能染在已有墨迹上**，轮廓外悬空 1 格会被 outline
        接边、把身体质心顶偏。
     ⑤ **`outline()` 必须是真·外描边**：早期实现用 `setdefault` 把轮廓色写回身体**边界格本身**
        （内描边），实测后果是落在轮廓上的五官/装饰被轮廓色吃掉——蛇的闭眼、刺猬的垂眼角、
        青蛙的眼泡线都在剪影边缘，画完即消失，表现为「sleep 帧看着还睁眼」。改为只向 4-邻域的
        **空白格**扩 1 格，代价是剪影整体外扩 1 格（65 张全部字节级变化，需重跑对照图复核）。
     ⑥ **五官墨色固定用 `BLACK`，禁止用 `pal["line"]`**：`line` 与外轮廓同色（snake `#004D40`
        轮廓亦 `#004D40`），闭眼画上去等于隐形；penguin 的 `line` 恰为 `#212121` 与 BLACK 同值，
        掩盖了这个坑——统一 BLACK 后两物种同时正确。
     ⑦ **状态五官必须在外层，特征分支只画自己的部件**：`snout`(pig)/`beak`(owl,penguin)/
        `frog_eyes` 三类分支原为 `if/elif/else` 的互斥支，一旦命中就**跳过**通用状态嘴/闭眼，
        表现为猪的 happy 与 idle 只差眼睛、企鹅张嘴永远同一个形状、青蛙全程睁眼睡（结构性缺陷，
        非参数调优问题）。另加 `PROP_LADDER` 道具降级阶梯（满碗→小碗→饭粒、双音符→单音符、
        Zzz→Zz）作为安全网，本轮实测 0 次触发但保留，防后续加物种时道具塞不下。
2. **PIL 加工脚本**（AI 侧，确定性工序）：`tools/pet_sprite/sprite_pipeline.py`
   - **默认直通路径（自有素材）**：`scale` 恒为 1.0，**禁止任何缩放**——4px 块被 0.12~0.95 这类
     非整数倍重采样会把 80px 身体压成 18px 且碎成多个孤立连通域，还反过来污染锚点解算；
     曾试在 cell 空间按中心点放大（zc）撑满画布，pitch 5.33px + 块宽 4px 留 1px 缝同样撕碎身体，
     已回退——「撑满画布」是设计常量的责任，不是重采样的责任。
   - **身体/道具分层贴回**：身体（alpha 最大连通域）做**无损整数平移**回 (48,48)，道具按草稿
     绝对坐标原位粘贴 → 彻底消除「连道具一起挪导致道具出画被裁」。道具一律不参与锚点与漂移计算
     （否则投喂帧会把整只身体顶离中线，实测错位 11px）。
   - `--adapt`（外部素材兜底）：色键抠图 → 全物种统一比例（身体目标框 + `fit_limit` 解析解，
     保证锚点可精确命中且不裁切）→ 与 idle 的身体掩膜 ±8px 互相关配准（IOU<0.35 放弃位移并告警）。
   - **帧间零漂移硬校验**（直通路径替代互相关配准）：各状态身体质心与 idle 比，阈值 1.0px
     = 两帧各自整数取整 ±0.5px 的叠加噪声；跨状态是瞬时切帧不做补间，>1px 即造型没同格起画。
   - **呼吸派生**：`idle_a/idle_b` 仅对 cat/dog/rabbit 派生（引擎 `breathRes` 只认这三档，
     其余物种派生即死资源），`idle_a`=原帧、`idle_b`=身体压扁 1px 脚线不动，台账标 `derived`。
   - **陈旧素材防呆**：起手清空 `out/`；收尾断言产出集合 == 契约闭集（65 张 = 59 投料 + 6 派生）。
   - alpha 二值化(阈值128 → 仅 0/255) → 输出 96×96 PNG-32 + 每物种联排预览 `out/_preview/<code>.png`
     + 台账 `out/_manifest.json`。
3. **人工终检（用户侧，判观感）**：机器只能证明「合规」，不能证明「好看」。
   `sprite_ascii.py` 在**无图像输入能力**的模型侧用 ASCII 字符画 + 连通域/质心统计核对造型是否
   在位（耳/尾/翅/白眼/喙），最终风格仍由用户看 `out/_preview/<code>.png` 判定；不满意就改画师
   设计常量重跑（外部手绘素材则回 Aseprite 修语义重心后走 `--adapt`）。**禁止**在运行时用
   offset 补丁救坏素材（A.6）。
   - 审阅物料固化在 `tools/pet_sprite/make_review_sheet.py`：`out/_contact_sheet.png`（全 65 张
     联排）+ `review.html`（暗底 + 状态色标），全局改动后加 `--against <旧目录>` 产出
     `out/_compare_sheet.png` **左右新旧对照长图**（130 格）。为什么必须做对照图：`outline()`
     这类改动会让 65 张全变，只给「变更清单」用户无法判断观感，而模型侧无图像输入能力，
     验收只能靠浏览器看预览页（`python3 -m http.server` 从仓库根起，页面用绝对路径）。
   - 观感整改的核对口径：**用格级颜色 dump，不信 ASCII 图例**——ASCII 按 1px 采样，2 格的
     闭眼线会被整体忽略，导致「改了没生效」的误判。
4. **映射填充**：AI 侧按文件名填充 `PetSpriteResMap`；缺动画帧填 `null`。
   当前状态：14 物种核心 4 帧全齐，cat/dog/rabbit 另具 blink/idle_a/idle_b（共 65 张，36.9KB）。

> ⚠️ 命名解析陷阱：`idle_a/idle_b` 含双下划线，解析必须**按状态闭集做后缀最长匹配**（`cat_idle_b` → code=cat, state=idle_b）；Python 侧与 CI 测试侧同规则（已修，早期用 `lastIndexOf('_')`/`rpartition` 会把 code 误判成 `cat_idle`）。

### A.6 CI 素材校验（JVM 单测，粗筛）

- 实现位置 `app-host/src/test/.../PetSpriteAssetContractTest.kt`，**手写 PNG 解码器**取 alpha：Android 单测 classpath 用 `android.jar`，已剥离 `java.awt`/`javax.imageio`，无法用 `ImageIO`（这是平台限制）。解码器仅接受 8bit 非隔行 RGBA/RGB/Gray，其余（16bit/调色板/隔行）直接判违规——即 Aseprite PNG-32 导出契约本身。
- 扫描 `drawable-nodpi/` 全部宠物 PNG：断言宽高恒 96×96；
- 中心胸区 5×5（46..50 × 46..50）存在不透明像素（锚点压在身体上）；
- Alpha 仅 0/255（全图逐像素，非抽检）；
- 同物种各核心帧**身体质心**（最大连通域质心，道具不参与）相对 idle 偏移 ≤2px；
- 命名/映射表一致性：文件名按状态后缀最长匹配解析，code ∈ 14 物种、state ∈ 封闭集合；
- 素材未入库时目录仅 `.gitkeep` → 遍历空集合天然通过，不阻塞引擎合入。
- 已用合成素材做过**双侧交叉验证**（Python 产物 → Kotlin 测试判绿），两侧几何规则同源。
- ⚠️ CI 只拦"忘记校正/尺寸错误"类低级事故，**不能替代 Aseprite 人工终检**。

### A.7 铁律

1. **禁止在 Android 运行时用 offset/translate 补丁"救"错位素材**——素材问题回 Aseprite 工序修死；运行时代码里唯一允许的位移是「整数缩放余数居中」与「角标叠加在固定头顶槽位」，二者都不是素材偏移补丁。
2. 业务零改动：本特性不得触碰 `PetStateManager` / `PetDecayEngine` / 协议 / Room / 事件日志。
3. 默认模式保持 `VECTOR_OLD` 不变；老存档 `PIXEL_RETRO` 值原样读取；新增枚举仅追加。
4. 14 物种清单：cat, dog, rabbit, penguin, hamster, panda, fox, dragon, sheep, hedgehog, **frog(70), pig(100), owl(150), snake(190)**（后 4 为本期静态追加）。


---

## 附录B：互动音频系统（预制音效 + 可选 TTS 文字播报）

> 需求来源：《InkLink-Pet 内置互动音频系统完整设计文档 Final-Rev1》。
> 落地路线：**按文档全量实现**（22/23 双码位 + MediaPlayer 封装 + TTS 工具类 + 10 个 wav + 全局开关），
> 但三处与文档字面不同——都以项目既有铁律/现实约束为准，逐条记录如下，避免后来人以为是"实现走样"。

### B.1 与既有实现的关系（先盘存量，再决定新增什么）

落地前实测确认，文档里的"新功能"有一半已经存在，本次真正新增的只有**远端触发这半边**：

| 文档条目 | 既有实现 | 本次处置 |
|---|---|---|
| §九 受控端 TTS 工具类（初始化/可用性检测/朗读/销毁/降级） | `tts/LocalTtsManager.kt`（多引擎轮询、中文缺失英文兜底、音频焦点、`pendingSpeech` 补读、`onUnavailable`→文字气泡） | **保留并包一层**：新增 `audio/PetTtsGate.kt`（进程级单例 + 三态探测 + 限流），内部委托 `LocalTtsManager` |
| §九 MediaPlayer 封装 | `util/SoundEffectManager.kt`（ToneGenerator chiptune，10 类目，零资产，本地游戏化反馈用） | **并存不合并**：新增 `audio/RawSoundPlayer.kt` 负责网络指令触达的 10 个 wav；chiptune 继续服务 COIN/GIFT/HATCH/LEVEL_UP |
| §四 CMD_PET_EVENT(23) + ACK 状态同步 | `PET_INTERACT_CMD(31)` + `PET_INTERACT_ACK(32)`（带 `petSnapshot` 全量快照） | 23 新增，但**结算汇入 31 的同一核心**（B.3） |
| §四 传文本让受控端朗读 | `REMOTE_TASK_SEND(45)` + `content` 自由文本 → 受控端朗读 | 保留 45 通道；本次给它套上门控（不再每次广播 new 引擎）+ 120 字上限 |
| §六 主控 5 个互动按钮 | `PetDetailActivity` 已有投喂/玩耍/清洁/学习/睡眠/治疗（走 31） | 新增 23 专用事件行，**不动 31 那批按钮**（31 支持 count/foodType，如长按 5 连投，23 不替代它） |

### B.2 三处刻意偏离文档字面的裁决（重要）

1. **不新建第二个 TTS 引擎实例。** 文档 §九 要求"受控端 TTS 工具类"，若按字面另起 `TextToSpeech`，
   同进程会出现两到三个引擎实例（UI 一个、`TaskPlayActionReceiver` 每次广播一个、新工具类一个）：
   各自 bind 系统语音服务、各抢一份音频焦点，表现为"两句同时在念、互相掐断"。
   `PetTtsGate` 做成单例并委托既有 `LocalTtsManager`；`PetMainActivity` 与
   `TaskPlayActionReceiver` 一并迁入门控（顺带消掉"每次广播 new 引擎、靠回调 release"的泄漏）。
2. **告警音不被娱乐音抢占。** 文档 §五 写"播放重叠：新音频优先，终止上一段"。这与
   android-lead 的**音频通道隔离**冲突（宠物音效/TTS→`STREAM_MUSIC`，强控告警→`STREAM_ALARM`
   且强行打断 TTS）。实现取铁律：`alert_warn/alert_call` 走 `USAGE_ALARM`，播放前先 `tts.stop()`；
   反向（喂一口饭掐断报警）被 `RawSoundPlayer.Result.SKIPPED_BY_ALARM` 显式拒绝。
3. **`ttsText` 是网络注入面，必须截断。** 文档只写"简短文本"。零信任原则下按硬规则实现：
   双端共用 `SoundProtocol.sanitizeTts()`（丢控制字符、换行折空格、**码点边界**裁到 40 字），
   播报限流 1.5s，ID 全白名单（未知 `soundId/eventId` 拒绝、不兜底播放）；
   主控端开关关闭时**连文本都不发出**。远程任务文案另设 120 字上限
   （`TASK_TTS_MAX_LEN`：家长写的任务文本本就更长，沿用 40 会静默截断——这是新加约束，非收紧既有约定）。

### B.3 23 与 31 的单一路径裁决（防双扣数值）

`CMD_PET_EVENT(23)` 的 `event_feed` 与 `PET_INTERACT_CMD(31)` 的 `FEED` 是同一件事。
**幂等只按 `msgId` 去重，两条不同消息的 msgId 天然不同**，所以"各写一套结算"必然双扣饥饿。裁决：

- 从 `onPetInteractCommand` 抽出 `handleInteractCore(action, count, foodType, replyTo, sleepTarget)`，
  31/23 共用；数值增减、落盘、`_petEventFlow` 广播、回 32 ACK（含 `petSnapshot`）**只此一处**。
- 23 额外加一层**语义幂等** `eventSemanticGuard`：key = `eventId@triggerTs`（TTL 10s）。
  只 collapse 同一逻辑事件的重投，用户连点两次因 `triggerTs` 不同不会被误杀。
- `event_sleep`/`event_wakeup` 用**目标态**语义（`sleepTarget`）：已达目标不再 toggle，
  否则"唤醒"连点两下会把宠物又睡回去；31 传 null 保持原 toggle 行为不变。
- `event_hungry_alert` 标 `hostReported=true`：受控端收到这个 ID 的回声**直接丢弃**（防事件环），
  且不改变任何数值（数值已经低了，再扣就是惩罚用户）。
- 顺序按文档 §五：**先结算状态 → 再播音效 → 再判播报**；音频全链路失败都不回滚状态、不抛异常。

### B.4 素材工序（`tools/pet_audio/`）

1. **不用 AI 音频生成**：本沙箱无音频生成工具，且文生图服务实测 `insufficient balance`——
   额度不可靠的能力不能当交付关键路径。改 `generate_sfx.py` 确定性合成：numpy 加法谐波限带合成
   （朴素 `sign(sin)` 方波有混叠刺声）+ 每音必带升余弦淡入淡出（零交叉硬切 = 可听爆音）+
   固定随机种子（噪声可复现）。零版权、可重跑、风格统一。
2. **裁尾即文档《后处理流水线》第 1 步**："裁剪头尾静音"做成 `trim_tail()`，只裁尾不裁头
   （每个配方首个事件都从 t=0 起，裁头会切掉淡入产生爆音）。裁完断言仍在 0.5–1.5s。
3. **合规靠代码证明，不靠人耳抽查**：`check_wav()` 逐条断言 16bit/44100/mono/时长/峰值 −1dBFS/
   无直流/首尾无静音垫。`--check DIR` 模式对已入库目录跑同一套规则（与 CI 同源）。
4. 过程中自检连续抓到 9 个配方的**尾部静音垫**（0.05–0.25s）与 2 个裁尾后越下限的配方；
   另修正了校验器自身的一处口径错误：最初用"最后 20ms 峰值"判"无尾音空白"，
   会把一切合法淡出误判为违规——正确的量是**首尾静音游程长度**（≤5ms / ≤30ms）。
5. 成品 10 张共 **656.6KB**（APK 相应增大），台账 `tools/pet_audio/out/_manifest.json`
   记 declared/actual 时长、peak、rms、字节数。

### B.5 CI 双层校验

- `common/SoundProtocolTest`（14 用例）：码位 22/23 不回退、两张白名单闭集与文档 §二 逐字相等、
  每个 `eventId` 的 `soundId ∈ RAW_NAMES`、`action` 只能取 31 号指令的既有取值域
  （否则会在受控端 `when` 的 else 分支被**静默当成抚摸**）、`sleeping` 只对 SLEEP 有效、
  `hostReported` 必无 action、`sanitizeTts`/`clamp` 的**代理对不被切半**、payload 往返 + 旧端缺字段走默认值。
- `app-host/RawSoundContractTest`：`res/raw` 文件集合 **== `SoundProtocol.RAW_NAMES`**（不多不少）、
  手写 RIFF 解析逐条验 16bit/mono/44100/时长/峰值/直流/首尾静音/削顶、
  `R.raw` 常量逐个存在（防"加了协议 ID 忘了放文件"）。
- 已做**变异测试**证明门禁会红：① 多一个合法非契约文件 `legacy_alert.wav` → 集合断言 FAILED；
  ② 篡改 `pet_feed.wav` 的 fmt channels=2 → 规范断言 FAILED；恢复后全绿。
  另发现 aapt 会直接拒绝下划线开头的资源名（第三层保险，但不是可读错误信息，故仍靠测试兜）。

### B.6 已知偏差与待办

- 文档 §六"主控弹窗提醒，主控本地播放提示音"与 §七.1"wav 只存受控端"矛盾。
  **取 §七.1**：主控端不存音频，饥饿上报只弹窗 + 一键补喂；受控端自己播 `pet_hungry`。
- 音量一致性目前是**峰值归一（−1dBFS）**，RMS 0.27–0.46 仍有差异（音符密度不同）。
  若真机听感忽响忽轻，需改 LUFS/响度归一化（待办，非阻断）。
- 按文档用 `MediaPlayer`（非 `SoundPool`）：代价是每次播放都要新建/释放解码器，且 `prepare()`
  同步准备有可感知的首帧延迟（**具体毫秒数未在真机实测，勿当结论引用**；`SoundPool` 是预加载
  解码、天然更适合 0.5~1.2s 短音效）。已用"播放前 release 上一个 + 完成/出错/抢占三路径 release"
  约束生命周期。若真机觉得反应慢，改 `SoundPool` 是明确的退路，但要先解决"与
  `SoundEffectManager` 两套 SoundPool 并存"的问题。
- APK 实测（release）：10 个 wav 在包内 **未被 aapt 压缩**（条目字节数与源文件逐一相等，共 656.6KB）。
  这是必须验的一条——若被 deflate，`openRawResourceFd` 拿不到裸 fd，`MediaPlayer` 会直接播不出来。
  同时 AGP 会把资源路径混淆成 `res/0k.wav`，本实现用 `R.raw` 整型 ID 而非按名查找，故不受影响。
- `SoundEffectManager` 里 `soundPool` 创建后从未使用、`play()` 每音符 `new Handler().postDelayed`
  且 mute/release 不取消已排队音符——本次**未动**（不在音频指令范围内），列为待办。
- 受控端无"家长侧禁止播报"本地开关（文档只要求主控端全局开关）；`enableTts` 完全由指令携带。
- 真机联调（阶段6）与局域网/Ably 双通道联调**未做**，见 `CHECKLIST_PET_AUDIO.md`。

### B.7 顺带查出并修掉的同类缺陷：冷却/限流误用墙钟

写阶段7 待补单测时把新守卫提纯成可注入时钟，顺手普查了这个缺陷类，命中 3 处（其中 2 处是我本次新写的）：

| 位置 | 判据形状 | 墙钟被往回调时的后果 |
|---|---|---|
| `IdempotentController`（**全 App 入站指令去重唯一真相源**，此前零测试） | `now - existTime < ttlMs` | 负值恒 `< ttlMs` → 条目**永不过期**，且 `cleanExpired` 同样判不出来清不掉 → 该 msgId 之后永久被吞，日志干净，重启才"自愈" |
| `PetTtsGate` 播报限流 | `now - lastSpeakTs < 1500` | 文字播报**永久静默**、音效照常，症状极像"TTS 引擎坏了" |
| 受控端饥饿提醒冷却 | `now - lastHungerAlertTs < 600s` | 宠物饿死也不再提醒家长 |

儿童手表的墙钟是**随时会被改**的（家长 App 设时间、时区变化、NTP 校正、孩子手动改），所以这不是理论风险。
修法：新增 `common/utils/MonoClock.kt`（`MonoClock.now() = SystemClock.elapsedRealtime()`，单调且含深睡）
与 `MonoThrottle(windowMs, timeMs)`；`IdempotentController` 加第三个构造参数 `timeMs`（默认单调，
两个既有调用点零改动）。**异常分支的取舍已在 KDoc 写明**：时钟倒转一律按"窗口已过/放行"处理，
因为对互动类动作"多做一次"（孩子多吃一颗糖）的代价远小于"永久静默失效且无日志"。

单测 16 例（`IdempotentControllerTest` + `MonoThrottleTest`，注入假时钟），含 TTL 边界、容量淘汰、
`eventId@triggerTs` 语义守卫口径（"同事件重投必吞、用户连点必放"，最容易被后人改成只按 eventId 去重）。
复盘三遍中钉死的三个细节（写下来防后人再改回坏语义）：
- **异常分支取舍**：时钟倒转一律按"窗口已过/放行"。对互动类动作，"多做一次"（孩子多吃一颗糖）
  代价远小于"永久静默失效且日志干净"。默认时基单调，该分支生产不可达，但有回归测试钉着。
- **哨兵值**：`MonoThrottle` 用 `Long.MIN_VALUE` 表示"从未放行"，**不能用 0**——
  `elapsedRealtime()` 开机后本来就接近 0，用 0 当哨兵会让开机头几秒的判据全部错乱。
  既有 `ReportThrottler` 正是 `lastStatusTime == 0L` 哨兵，将来若有人把它迁到单调时钟必先踩这个坑（已在此留警示）。
- **限流次序**：`PetTtsGate` 的 tryAcquire 放在懒建引擎实例**之后**、真正提交朗读之前。
  与旧实现相比唯一差异是"被限流时仍会懒建实例"，而首次调用必放行 ⇒ 该差异分支不可达，已核。
- `lastSkipReason` / `remainingMs()` 只进本地事件日志，**不外泄进 payload**（对端无基准可比）。

变异测试证明回归断言有效：把两处判据改回旧墙钟语义（`age < ttlMs` / `delta < windowMs`）→
恰好 `时钟回拨不得吞掉后续消息` 与 `时钟回拨不得永久卡住` 两条 FAILED，还原即全绿。

**刻意未改**：
- `SoundPayloads.timestamp` / `triggerTs` 保持墙钟——它们是**跨设备线格式时间戳**（对端时钟域），
  语义是"那台设备什么时候触发的"，只作身份与展示、从不参与本地差值判据。改成 elapsedRealtime
  反而是灾难（两台设备的单调时钟基准互不可比）。已就地加注释防后人"顺手统一"。
- `AlarmManager.setInexactRepeating(RTC_WAKEUP, now + 60_000L, ...)` 保持墙钟：该 API 的触发时刻
  参数本就必须是墙钟域（普查时一度误判为同类缺陷，查证后排除）。

**待用户裁决的遗留项（同型、但在连接保活路径上，超出音频特性范围）**：
`InkForegroundService` 心跳 45s 超时判定 `System.currentTimeMillis() - lastPongTs > 45_000L`。
墙钟回拨会使其恒为负 → **永远判不出掉线**，表现为"主控端显示在线但指令石沉大海"。
证据面：`lastPongTs` 只有 4 处引用（初始化 + 2 处赋值 + 1 处读取），**全为本地比较、不与对端时间戳
相减**，故换成单调时钟是自包含改动。未顺手改的原因不是难度，而是它属于连接保活语义，
需要真机双通道（Ably 断网/WS 切后台）验证「真掉线仍能被判出、且不误杀正常心跳」——沙箱无此条件。

---

## 附录C：云朵 LCD 场景宠（PIXEL_SCENE 模式 · 2026-09-05 拍板）

> 本附录启用第 5 档渲染模式 `PIXEL_SCENE`。与附录A 同纪律：业务层、状态机、数据库、协议零改动；
> 仅渲染层新增分支 + 复用 `PetCatalog.SCENES`（场景 id 唯一数据源）。当前为**模式驱动**：选中该模式
> 即以云朵角色渲染；待引入「云朵」物种后再启用「云朵物种自动路由」（见 C.7 待办）。

### C.1 三条硬性契约

1. **场景底图契约**：一律 250×250px、**全不透明**（alpha 仅 255，禁半透明杂边）。整屏侧视室内房间，
   画面墙地分界在 **y=226**（地面线 = 宠物脚线，约 0.9h）。
2. **角色帧契约**：一律 96×96px、透明底、alpha 二值化（仅 0/255）。云朵角色视觉重心压画布中心
   **(48,48)**；静态表情帧质心相对 `idle_a` 偏移 ≤2px（动作帧的跳起/低头/压扁是渲染层故意位移，只校验
   水平漂移与中心锚点）。
3. **像素契约**：整数倍放大 + 硬边无插值（`isFilterBitmap=false`），与 A.1/A.4 同规则。

### C.2 命名与状态闭集

- 角色帧：`cloud_<state>.png`，全小写下划线，19 张状态闭集：
  - 呼吸/眨眼：`idle_a / idle_b / blink`；
  - 单帧静态表情：`hungry / sad`；
  - 多帧动作：`happy_1..3 / sleep_1..2 / eat_1..3 / read_1..2 / clean_1..2 / annoy_1..2`。
- 场景底图：`scene_<id>.png`，id ∈ `bedroom / living_room / windowsill`（与 `PetCatalog.SCENES` 严格一致）。
- 存放目录：`app-host/src/main/res/drawable-nodpi/`（与附录A 同目录、同防 DPI 缩放理由）。

### C.3 渲染决策（场景底图 + 程序道具 pre/post + 角色帧固定锚点）

`PIXEL_SCENE` 模式自上而下（`PetSpriteView.drawScenePet`）：

1. `lifeStage == EGG` → 整只回退 `PIXEL_PNG`（蛋形态无云朵角色）；
2. 场景底图缺失 → 整只回退 `PIXEL_PNG`；
3. 场景底图 250×250 **整数倍放大 + 余数居中**（`s = floor(min(w,h)/250)`，≥1）；
4. 程序道具按 pre/post 分层叠加（250 坐标，硬边矩形/椭圆，色彩见 `tools/pet_blob/make_actions250.py`）：
   - pre（在角色身后）：睡眠垫（SLEEP）；
   - post（在角色身前）：饭碗（EAT）、玩具球（HAPPY）、故事书（READ）、清洁泡泡+水桶（CLEAN）、怒气十字（ANNOY）；
5. 角色帧**固定锚点**：水平居中 x∈[77,173]，静息脚底对齐地面线 y=226（帧内 `FEET_ROW=86`，帧顶落在 y=140）；
   动作帧的垂直位移由素材自带，**禁止运行时 offset 补丁**。

状态 → 云朵帧映射（`PetSceneMap.CLOUD`）：

| 业务状态（pose/mood/sleeping） | 云朵状态键 | 帧序列（每帧时长） |
|---|---|---|
| sleeping 或 SLEEP | SLEEP | sleep_1/2（0.6s） |
| EATING | EAT | eat_1/2/3（0.4s） |
| STUDY | READ | read_1/2（0.5s） |
| CLEAN | CLEAN | clean_1/2（0.4s） |
| ANNOY | ANNOY | annoy_1/2（0.35s） |
| PLAY/HAPPY | HAPPY | happy_1/2/3（0.4s） |
| HUNGRY | HUNGRY | hungry（静态） |
| SAD/WEAK | SAD | sad（静态） |
| SICK | SICK | sad（无专属帧，回退） |
| 其它（IDLE） | IDLE | idle_a/idle_b 呼吸交替（0.7s），`blinkHold>0` 时 blink |

### C.4 Android 侧加载铁律

- 角色帧复用附录A A.4：`inScaled=false`、`isFilterBitmap/isDither/isAntiAlias=false`、96×96 断言、
  `LruCache` 按需解码（复用 `PetSpriteView.loadSpriteBitmap`）。
- 场景底图独立 `LruCache`（3 张 × ~250KB），解码后断言 `width == height == 250`。

### C.5 素材生产流水线（`tools/pet_blob/`）

1. **场景底图**：`make_scenes.py` 程序化绘制 3 张 250×250 侧视房间（卧室/客厅/窗台），
   墙地分界 y=226；旧版 `scene_*.png` 与新 `scene250_*.png` 并存，入库用 250 版。
2. **角色帧**：96×96 云朵角色 19 帧放 `preview/raw/`，与场景合成用 `make_actions250.py`
   产出 `act250_<scene>_<act>_<n>.png` 联排预览（`acts250_sheet.png`，道具 pre/post 分层演示），
   供浏览器预览页（`python3 -m http.server` 从仓库根起）做观感验收。
3. **落盘**：`deploy_assets.py` 对角色帧/场景做 alpha 二值化（阈值 128 → 0/255）后按
   `cloud_<state>.png` / `scene_<id>.png` 拷入 `drawable-nodpi/`。

### C.6 CI 素材校验（JVM 单测，粗筛）

复用 `PetSpriteAssetContractTest.kt`（手写 PNG 解码器，同 A.6 平台限制），在附录A 断言之上追加：

- 文件集合 == 契约闭集（19 云朵帧 + 3 场景底图）；
- 场景底图：250×250、alpha 无半透明（仅 0/255）；
- 云朵帧：96×96、alpha 无半透明、中心 5×5 不透明（锚点压在身体上）；
- 云朵静态表情帧质心相对 `idle_a` 偏移 ≤2px。

### C.7 铁律与待办

1. **禁止运行时 offset/translate 补丁修错位素材**——素材问题回 `tools/pet_blob/` 工序修死。
2. 业务零改动：不触碰 `PetStateManager` / `PetDecayEngine` / 协议 / Room / 事件日志。
3. 老存档兼容：`PIXEL_SCENE` 枚举仅追加，旧值读取原样。
4. **待办（云朵物种自动路由）**：引入「云朵」物种后，渲染层判断 `def.code == "cloud"` 时强制走
   本路径（无视全局模式偏好），其它物种选本模式缺素材回退 `PIXEL_PNG`。当前为模式驱动，二者行为
   等价地落在「缺素材回退 PIXEL_PNG」这一兜底上。
