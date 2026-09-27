# app-host 孩子端（受控端）

孩子端三合一：电子宠物养成 + 学习乐园 + 家长管控受控侧。全业务由前台服务统一托管，约 12800 行 Kotlin（含测试）。

## 结构

```
app-host/src/main/java/com/inklink/host/
├── InkHostApplication.kt    # 设备ID/TransportManager(默认ably)/聊天仓/好友仓初始化
├── service/
│   └── InkForegroundService.kt   # 1216行 唯一真相源
│       # 传输监听+约25种消息路由 / GPS采集上报(8m/3s节流) / 围栏判定 /
│       # 语音对讲 / 强控响铃 / 任务队列 / 宠物结算handleInteractCore /
│       # 离线队列补发 / 30s PING+45s超时 / 多层保活
├── state/                   # 宠物域
│   ├── PetStateManager.kt   # 979行 单例状态机：addReward唯一入账/六动作/性格/生命闭环
│   ├── PetDecayEngine.kt    # 双时钟衰减 + 浮点余量防丢帧 + 后台休眠豁免
│   ├── PetClock.kt          # 前后台时钟翻转（onResume/onPause 驱动）
│   ├── PetCatalog.kt        # 商品唯一数据源：21物种/7道具/4装饰/3场景/每日目标
│   ├── PetArcadeMap.kt / PetSceneMap.kt / PetSpriteResMap.kt  # 素材映射+三级回退
│   ├── GpsTreasureHunter.kt # GPS寻宝（80m位移+60s冷却）
│   └── HostScreenState.kt   # Service↔UI 共享状态 + 画面缓存自愈
├── pet/PetAiEngine.kt       # 行为树：8-20s决策 虚弱>睡眠>抱怨>性格动作>待机
├── learning/LearningManager.kt  # 495行 学习单例：出题/结算/落库/上报47/护眼/金币上限
├── data/                    # Room
│   ├── PetDatabase.kt       # v2，inklink_pet.db，主线程查询+破坏性迁移
│   ├── PetEntities.kt/PetDao.kt        # pet_bag/event_log(环形500)
│   └── LearnEntities.kt/LearnDao.kt    # learn_progress/wrong_book/daily_stats
├── game/PetMiniGameManager.kt   # 猜拳/翻牌/转盘判定 + 每日前8局防刷
├── friend/FriendRepository.kt   # friends.json + Presence + GameInviteSession
├── task/TaskQueueManager.kt     # task_list.json 上限20 淘汰策略
├── queue/OfflineEventQueue.kt   # offline_events.json 上限50 FIFO，联网drain
├── audio/                   # RawSoundPlayer(10wav)/PetTtsGate(单例门控)/PetAudioFeedback
├── tts/LocalTtsManager.kt   # 中→英→多引擎遍历，音调1.1语速0.95
├── security/PinSecurityManager.kt  # EncryptedSP加盐哈希/5错锁2min/HMAC防重放
├── receiver/                # BootReceiver(开机)/AlarmReceiver(60s自愈)/
│                            # PetWakeupReceiver(4h促活)/TaskPlayActionReceiver(通知播任务)
├── ui/
│   ├── PetMainActivity.kt   # 2212行 Launcher：HUD/手势/五按钮/商店4tab/游戏中心/
│   │                        # 背包/好友/聊天/学习入口/家长后台入口
│   ├── HostActivity.kt      # 462行 家长管控后台：二维码/投屏/响铃/传输模式切换
│   ├── ChatActivity.kt / FriendActivity.kt
│   ├── learning/            # 8个原生学习 Activity（无 WebView）
│   └── view/                # PetSpriteView(1519行像素引擎)/PixelSpecies/
│                            # PixelProgressBarView/SpinWheelView
└── util/                    # SoundEffectManager(ToneGenerator chiptune)/HapticUtil
```

## 关键文件

| 文件 | 目的 |
|------|------|
| `service/InkForegroundService.kt` | 一切业务结算与消息路由的归属地；改消息处理先看 route 分发块 |
| `state/PetStateManager.kt` | 宠物数值唯一写入口；addReward/六动作/衰减 settle |
| `learning/LearningManager.kt` | 学习域唯一入口；finishLesson 结算 + 艾宾浩斯 + 护眼 |
| `ui/view/PetSpriteView.kt` | 宠物渲染；素材三级回退链（PIXEL_PNG → arcade → 程序化） |
| `data/PetDatabase.kt` | Room v2；加表须升版本（当前破坏性迁移策略） |

## 依赖

**依赖**：project(:common)、appcompat、constraintlayout、material、dynamicanimation、konfetti-xml、security-crypto、zxing-core、room(ksp)
**被依赖**：无（叶子 Application 模块）

## 规范

- UI 只订阅 SharedFlow 展示；一切业务修改经 Service/Manager 单例
- 音频通道：预制 wav(网络指令) 与 chiptune(本地游戏化) 分离；告警 STREAM_ALARM 可打断 TTS
- 保活：前台服务类型按已授权限动态组合（MICROPHONE/LOCATION，全未授权兜底 DATA_SYNC），Android 14 兼容
- 素材契约测试（PNG/WAV）在 src/test，改资产必跑
- 单测 6 类：PetDecayEngineTest/PetGamifyKernelTest/PetLifeLoopTest/PetSpriteAssetContractTest/PetArcadeAssetContractTest/RawSoundContractTest

## 添加新文件

### 新增学习 Activity

1. 放 `ui/learning/`，继承 AppCompatActivity，exported=false 竖屏
2. 出题走 LearningManager，结算走 finishLesson；入口受 LearningHubActivity.guardPass 拦截
3. 在 AndroidManifest 注册（参照现有条目）

### 新增 Room 表

1. `data/` 新 Entity + DAO 方法；PetDatabase 升版本 + migrations（当前为破坏性迁移，注意丢数据风险）
2. 写操作走对应 Manager 单例协程，勿扩散主线程写入
