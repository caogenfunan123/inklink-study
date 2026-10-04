# InkLink 接口与协议文档

> 本文是双端通信协议、音频帧格式、传输配置、发现协议与素材契约的唯一明细速查。
> 单真值源在代码：`common/src/main/java/com/inklink/common/protocol/`。改协议先改代码，再同步本文。

## 1. 消息载体 InkMessage

文件：`common/.../protocol/InkMessage.kt`

| 字段 | 类型 | 说明 |
|------|------|------|
| type | Int | MessageType 码位 |
| payload | String? | JSON 载荷（见第 4 节） |
| audioData | ByteArray? | 仅局域网语音帧使用；文本序列化时强制剥离 |
| targetDeviceId | String? | 空=广播；非空=定向（Ably 模式客户端过滤） |
| fromDeviceId | String? | 发送方设备 ID |
| address | String? | 传输层附加地址 |
| msgId | String | 默认 `前缀-时间戳-自增seq`，幂等去重键 |
| timestamp | Long | 发送时间 |

- 编解码：`MessageCodec`（Gson 单例）。`encodeText` 强制剥离 audioData；`decodeTextOrNull` 失败返回 null。
- 单条上限：`MAX_MESSAGE_BYTES = 64 * 1024`（Ably 限制，TransportManager 发送前硬校验超限丢弃）。
- 工厂：`InkMessage.text()` / `control()` / `audio()`。

## 2. MessageType 全表（52+99 码位，勿改编号）

文件：`common/.../protocol/MessageType.kt`。方向约定：受=孩子端，主=家长端。

| 码 | 名称 | 方向 | 用途 / 载荷要点 |
|----|------|------|----------------|
| 1 | TEXT | 主→受 | 投屏文本，payload 即文本 |
| 2 | IMAGE | 主→受 | ImagePayload：mode(base64/url)/data/url |
| 3 | GPS_REPORT | 受→主 | GpsReport：lat/lng(WGS-84)/speed/time/accuracy/altitude/bearing/provider/quality/satelliteCount |
| 4 | CMD_CLEAR | 主→受 | 清屏 |
| 5 | GEOFENCE_CONFIG | 主→受 | GeofenceConfig：lat/lng/radiusMeters（WGS-84） |
| 6 | ALERT_ENTER | 受→主 | 围栏进入告警 |
| 7 | ALERT_EXIT | 受→主 | 围栏离开告警 |
| 8 | AUDIO_DATA | 双向 | 实时语音；文本帧不承载，走二进制帧（第 6 节） |
| 9 | AUDIO_START | 主→受 | 发起语音对讲 |
| 10 | AUDIO_STOP | 主→受 | 挂断，带 reason(NO_PERMISSION/BUSY) |
| 11 | REQUEST_GPS | 主→受 | 请求单次定位 |
| 12 | CHAT_TEXT | 双向 | 聊天文本 |
| 13 | CHAT_IMAGE | 双向 | 聊天图片（Base64 压缩后 ≤60000 字符） |
| 14 | CHAT_AUDIO | 双向 | 聊天语音（AMR Base64，>60000 字符拒发） |
| 15 | CMD_ACK | 受→主 | AckPayload：ackMsgId/success/code/errorMsg；错误码 0/101 音量受限/102 权限/103 执行异常/105 定位关闭/106 电池优化/107 幂等丢弃 |
| 16 | CMD_RING | 主→受 | RingPayload：durationSec/volumePct/loopCount |
| 17 | CMD_STOP_RING | 主→受 | 停止响铃 |
| 18 | DEVICE_STATUS_REPORT | 受→主 | batteryPct/isCharging/netType/signalDbm/batteryOptimized/locationPermission/audioPermission/isRinging/memFreeMb/appVersion/ttsAvailable(三态) |
| 19 | REFRESH_SCREEN_DEEP | 主→受 | 深度刷新（墨水屏场景） |
| 20 | SCREEN_CACHE_RESTORE | 双向 | 画面缓存自愈 |
| 21 | PUSH_ALERT | 主→受 | title/content/ring/durationSec(默认15)/needNotification |
| 22 | CMD_PLAY_SOUND | 主→受 | PlaySoundPayload：soundId 白名单 4 个（alert_call/alert_notify/alert_warn/sound_ack） |
| 23 | CMD_PET_EVENT | 主→受 | PetEventPayload：6 事件绑定（feed/touch/happy/sleep/wakeup/hungry_alert）；与 31 语义重叠，受控端必须汇入 handleInteractCore 防双扣 |
| 30 | PET_STATE_SYNC | 受→主 | 活跃宠物全量状态，本地变化 3s 节流 |
| 31 | PET_INTERACT_CMD | 主→受 | action(FEED/PLAY/CLEAN/SLEEP/LEARN/HEAL)/foodType/count(强制1-5)/triggerTs |
| 32 | PET_INTERACT_ACK | 受→主 | success/delta/petSnapshot/ackTs |
| 33 | PET_GAME_INVITE | 双向 | gameType(RPS)/inviteId；好友频道白名单内 |
| 34 | PET_GAME_ACTION | 双向 | inviteId/actionData；好友频道白名单内 |
| 35 | PET_ALERT_EVENT | 受→主 | alertType(GEOFENCE_EXIT 等)/description，宠物受惊联动 |
| 36 | CMD_RESET_HOST_PIN | 主→受 | newPinHash/authSignature(HMAC-SHA256(pairingKey, newPinHash+":"+ts))/timestamp(±60s 防重放) |
| 40 | PET_BAG_SYNC | 受→主 | 背包全量：activePetId/petList[]/coin/itemStock/moments/memorials；重连后补发 |
| 41 | PET_SWITCH_ACTIVE | 受→主 | petId，切换前先结算旧宠衰减 |
| 42 | PET_SHOP_BUY | 受→主 | itemId/itemType/success/newCoin |
| 43 | PET_BAG_INTERACT | 受→主 | targetPetId/subType(LOCAL_VISIT/FRIEND_VISIT/CALL_VISIT/TEASE)/triggerTs；好友频道白名单内 |
| 44 | PET_REMOTE_GIFT | 主→受 | giftType(item/deco/buff/coin)/itemId/count；15 项目录见 PetDetailActivity |
| 45 | REMOTE_TASK_SEND | 主→受 | RemoteTaskPayload：taskId/content(≤120字去控制字符) |
| 46 | REMOTE_TASK_ACK | 受→主 | taskId/status(RECEIVED/PLAYED)；PLAYED 触发家长端 10 金币奖励闭环 |
| 47 | LEARN_PROGRESS | 受→主 | LearnProgressPayload（第 4.1 节） |
| 48 | HOMEWORK_ASSIGN | 主→受 | HomeworkAssignPayload：taskId/module/title(≤120字)/count/params/deadline。**协议就绪，主控端未实现** |
| 49 | HOMEWORK_ACK | 受→主 | taskId/state(RECEIVED/DONE)/score。**协议就绪，主控端未实现** |
| 50 | PING | 受→主 | 30s 周期心跳，deviceId/timestamp |
| 51 | PONG | 主→受 | 心跳应答，45s 未收到判离线 |
| 52 | ERROR_RESPONSE | 双向 | refMsgType/errorCode/message |
| 99 | HEARTBEAT | 双向 | 极简保活：seq/batteryPct/isAlive；局域网连接建立即发一条完成注册 |

> 码位纪律：enum 重复码位会让 `fromCode` 静默覆盖（后定义覆盖先定义）；历史已发生两次冲突（7、10-12）。新增码位先查表登记，见 DEVELOPER_GUIDE 常见任务。

## 3. 关键数据结构

### 3.1 PetItem / PetBag（`payload/PetPayloads.kt`）

PetItem 31 字段全量：四维 + health + exp/level/lifeStage + playfulness/affection + finalForm + decoId/sceneId + 浮点余量 remXxx + passedAtTs 等。PetBag：activePetId/petList/coin/itemStock/moments/memorials。Gson 序列化整包落库与上行；旧端缺省字段兼容（null 默认）。

### 3.2 SoundProtocol（`payload/SoundPayloads.kt`，双端音频唯一真值源）

- `SOUND_IDS`（22 号系统音效白名单）：alert_call / alert_notify / alert_warn / sound_ack
- `EVENT_BINDINGS`（23 号事件绑定 6 个）：feed / touch / happy / sleep / wakeup / hungry_alert
- `RAW_NAMES`：上述合计 10 个，与 `app-host/src/main/res/raw/*.wav` 严格一一对应（有契约测试）
- `ALARM_SOUND_IDS`：走 STREAM_ALARM 的子集（alert_call/alert_warn）
- `TTS_TEXT_MAX_LEN=40`、`TASK_TTS_MAX_LEN=120`、`TTS_MIN_GAP_MS=1500`
- `sanitizeTts()`/`clamp()`：码点边界裁剪，防 emoji 代理对切半
- `shouldSpeak` 三条件：AudioSettings 开关开 + 文本非空 + 通过限流

### 3.3 LearnPayloads（`payload/LearnPayloads.kt`）

| 码 | 载荷字段 |
|----|---------|
| 47 | scope(LESSON/DAILY)、module(HANZI/PINYIN/MATH/POEM/ENGLISH/REVIEW)、level、itemsDone、correctRate、minutesToday、coinsToday、weakTop5 |
| 48 | taskId、module、title(≤120字)、count、params、deadline |
| 49 | taskId、state(RECEIVED/DONE)、score |

## 4. 音频协议

### 4.1 实时语音参数（common/audio/AudioManager）

- 8000Hz / 16bit / 单声道，20ms 一帧 = 320 字节 PCM
- 采集侧静音检测（均值 <300 丢弃）；`AcousticEchoCanceler` 硬件消回声，不支持静默降级
- 播放侧 jitter buffer 约 5 帧：按序号排序、跳号静音填充、迟到帧丢弃
- 语音实时性优先：丢包静音填充，不重传

### 4.2 AudioPacket 二进制帧格式（局域网/中转 WS 二进制帧）

```
[0]     : byte 8（AUDIO_DATA 码位帧头标识）
[1..2]  : uint16 序号（小端，回绕按无符号比较 isNewerOrEqual）
[3..]   : PCM 8000Hz/16bit/单声道
```

HEADER_SIZE=3，MAX_SEQUENCE=0xFFFF；`fromFrame()` 校验帧头类型与最短长度，非法返回 null。

### 4.3 聊天语音（common/chat）

AMR_NB 12.2kbps @8kHz，按住录音松开返回字节 → Base64 进 14 号文本帧（上限 60000 字符）；播放写 cacheDir 临时文件后 MediaPlayer 播放。

## 5. 传输层接口

### 5.1 IMessageTransport / TransportManager

- 接口：`connect/disconnect/isConnected/sendMessage(InkMessage)/sendAudio(ByteArray)/setListener`
- TransportMode：`LOCAL`（host 起 WS Server）/ `RELAY`（公网中转 Client）/ `ABLY`（默认）
- TransportManager：
  - `switchMode` 先断旧再建新（sameTarget 短路：同模式同参数直接 return）
  - pending 重放：未连接时文本消息进 ArrayDeque，连上后 FIFO 全部重发；**语音帧不缓存**
  - 发送前自动补 `defaultTargetDeviceId`；64KB 硬上限校验

### 5.2 三模式参数

| 项 | LocalWsTransport | ServerRelayTransport | AblyRelayTransport |
|----|------------------|----------------------|--------------------|
| 角色 | host=SERVER 监听 8080；controller=CLIENT 连 `ws://ip:port` | 双端均 CLIENT，连自建中转（服务端已删，client 保留） | 双端均 Ably 订阅者 |
| 主频道 | — | — | `inklink-proto-ch01`，事件名 `ink-message` |
| 好友频道 | — | — | `inklink-pet-${pairingKey}`（pairingKey 默认 `inklink_default_key`，存 prefs；两端设置页「配对密钥设置」可改，须一致），白名单码位 {33,34,43} |
| 心跳 | 默认 15s，HeartbeatPolicy 覆盖（亮屏 10s/灭屏 30s） | 15s | 15s |
| 重连退避 | 1s shl attempt（上限 5），封顶 30s | 1s shl attempt（上限 10）+ 0-50% jitter，封顶 60s | Ably SDK 自带 |
| 定向 | targetDeviceId 单播 | 服务器按 target/from 路由 | echoMessages=false；target 非空且≠本机则丢弃；msgId 进 Ably 服务端幂等去重 |
| 语音 | 二进制帧支持 | 二进制帧支持 | sendAudio 空实现（只传文本 JSON） |

- Ably Key：`local.properties` 的 `ABLY_KEY` → BuildConfig.ABLY_KEY；controller 端优先读 prefs 可运行时覆盖。
- 设备 ID：`DeviceIdProvider`——ANDROID_ID 优先（含无效值 9774d56d682e549c 检测），UUID 持久化兜底。

## 6. 局域网设备发现 UdpDiscovery

- 端口 9000，扫描窗口 2s
- 请求：受控端 `startResponder` 监听广播；主控端向 `255.255.255.255` 发 UTF-8 文本 `INKLINK_DISCOVER`
- 应答：`INKLINK_REPLY:` + JSON `{"deviceId":"...","wsPort":8080}`
- 结果对象 `DiscoveredDevice(deviceId, ip, wsPort)`

## 7. 外部 Web API

| API | 文件 | 要点 |
|-----|------|------|
| 腾讯驾车路线 | app-controller/service/RouteApi.kt | WebService API，SK 签名=MD5(路径+升序参数+SK)；请求前 WGS-84→GCJ-02；响应 polyline 压缩解压（首点绝对+后续 1e-5 度偏移累加）；子线程回调 RouteInfo(points/distance/duration)。在线功能，断网隐藏入口 |
| 腾讯地图 SDK | app-controller ui 三页 | GCJ-02 展示；Key 经 manifestPlaceholders `${TENCENT_MAP_KEY}`，隐私初始化 `TencentMapInitializer.setAgreePrivacy → start` |

## 8. 资产契约（有单测钉死，改素材必跑）

| 资产集 | 位置 | 契约 |
|--------|------|------|
| 宠物精灵帧 | app-host/res/drawable-nodpi/ | 命名闭集（14 物种 × idle/hungry/happy/sleep [+cat/dog/rabbit blink/idle_a/idle_b] + 19 张 cloud_* + 3 张 scene_*.png 250x250）；96x96 画布、8bit 非隔行 PNG、Alpha 二值化、胸区锚点、帧间质心偏移 ≤2px（PetSpriteAssetContractTest） |
| 街机帧 | app-host/assets/pixel_arcade/ | 20 物种 × 4 状态(idle/hungry/happy/sleep) × 3 帧(_01.._03) = 240 张；256x256 RGBA 透明底 alpha 仅 0/255（PetArcadeAssetContractTest）；sheep 无素材走三级回退 |
| 音效 | app-host/res/raw/ | 文件集合与 `SoundProtocol.RAW_NAMES` 严格相等（10 个）；16bit 单声道 44.1kHz；时长 0.5-1.5s；峰值 -1dBFS；无直流；首静音 ≤5ms 尾静音 ≤30ms（RawSoundContractTest） |
| 学习数据 | app-host/assets/learning/ | 6 个 JSON：hanzi.json(3024 字，字段 char/pinyin/phrase/sentence/level)、poems.json(114 首)、pinyin.json(474 音节)、pinyin_questions.json(250 配对题)、english_words.json/english_sentences.json（未接线）。消费方仅 LearningManager |

## 9. 工具脚本接口（tools/）

| 脚本 | 调用 | 输入 → 输出 |
|------|------|------------|
| tools/learning/build_learning_assets.py | `python tools/learning/build_learning_assets.py` | 环境变量 REF_HANZI/REF_JOYE/REF_STUDYWORD 指定的参考仓库 → `app-host/src/main/assets/learning/*.json`。注意：会重新生成已退役的 `lib/strokes.json` 与 `lib/hanzi-writer.min.js`（WebView 时代产物），重跑后应手动剔除 |
| tools/pet_audio/generate_sfx.py | `generate_sfx.py [--deploy] [--check DIR]` | numpy 加法合成（固定种子 20260810 可复现）→ `tools/pet_audio/out/*.wav`；--deploy 拷入 res/raw；--check 供 CI 同源校验 |
| tools/pet_sprite/sprite_pipeline.py | 见 tools/pet_sprite/README.md | assets_draft/ 草稿 → out/ 96x96 成品 + _manifest.json；终检后人工拷入 drawable-nodpi |
| tools/pet_blob/deploy_assets.py | `python deploy_assets.py` | preview/raw 帧与场景 → drawable-nodpi 19 张 cloud_* + 3 张 scene_* |
| tools/pet_arcade/convert_arcade.py | `convert_arcade.py <src_dir> <out_dir>` | 白底 AI 图 → flood-fill 抠底 + 连通域清噪 → 256x256 RGBA 到 assets/pixel_arcade/ |
| tools/pet_blob/make_scenes.py 等 | 见脚本头部注释 | 生成审阅 preview 图，不直接落库 |

## 10. 新增码位登记规则

1. `common/.../protocol/MessageType.kt` 新增枚举（核对码位未被占用，fromCode 全量回查测试会校验唯一性）
2. 新载荷放 `protocol/payload/` 并补 round-trip 单测（参考 PetProtocolRoundTripTest）
3. 双端路由：app-host `InkForegroundService` 分发、app-controller `route()` 分发
4. 若走好友频道，更新 `AblyRelayTransport` 白名单并注释声明
5. 同步本文第 2 节表格与 `docs/IMPLEMENTATION_PLAN.md` 附 B 登记表
