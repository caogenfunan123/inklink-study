# InkLink-Pet 互动音频系统验收清单

需求来源：《InkLink-Pet 内置互动音频系统完整设计文档 Final-Rev1》。
架构裁决与偏离说明：`design.md` 附录B（**B.2 三处刻意偏离、B.3 单一路径裁决、B.6 已知偏差必须先读**）。

图例：`[x]` 已完成并有代码/测试证据 · `[ ]` 待做（含必须真机/人工的环节）

---

## 阶段1：存量盘点（写代码前，防止造出两套子系统）

- [x] 盘出现有音频资产化实现：`util/SoundEffectManager.kt`（SoundPool+ToneGenerator chiptune，10 类目）、`tts/LocalTtsManager.kt`（多引擎轮询+焦点+pendingSpeech+onUnavailable 降级气泡）、`service/SirenManager.kt`（STREAM_ALARM 响铃）
- [x] 盘出协议现状：22/23 **空闲可用**（21=PUSH_ALERT、30=PET_STATE_SYNC）；`PET_INTERACT_CMD(31)`/`ACK(32)` 已含 count/foodType/petSnapshot 全量状态同步；`REMOTE_TASK_SEND(45)` 已是"网络传自由文本 → 受控端朗读"通道
- [x] 确认 `res/raw` 此前不存在、主控端无任何音频文件（符合 §七.1）
- [x] 确认分发线程事实：`onTextMessage` 来自 Ably/WS 线程池（`ably-heartbeat`/`local-reconnect`），**不保证主线程** → 所有 MediaPlayer/TextToSpeech 构造必须 post 到主线程（否则 `new Handler()` 无 Looper 直接抛异常）
- [ ] 遗留：`SoundEffectManager.soundPool` 创建后从未使用、`play()` 每音符 new Handler 且 mute/release 不取消排队音符 → 本次未动，另列待办

## 阶段2：协议层（common，双端共用唯一真值源）

- [x] `MessageType` 追加 `CMD_PLAY_SOUND(22)` / `CMD_PET_EVENT(23)`，并在枚举处写明"23 必须与 31 汇入同一结算核心"的约束（不写出来，后来人一定会再抄一套数值）
- [x] `payload/SoundPayloads.kt`：`PlaySoundPayload(soundId,ttsText,enableTts,timestamp)`、`PetEventPayload(eventId,ttsText,enableTts,triggerTs)`——全部字段带默认值，Gson 对旧端向后兼容
- [x] `SoundProtocol` 白名单：4 个 soundId + 6 个 eventId + `RAW_NAMES`(10) + `ALARM_SOUND_IDS` + `EVENT_BINDINGS`（eventId → 播哪个音效 / 走哪个 31-action / 睡眠目标态 / 是否受控端上报型）
- [x] 零信任截断：`sanitizeTts()`（丢控制字符、换行折空格、裁 40 字）+ `clamp()` **按码点边界裁**（朴素 `take` 会把 emoji 代理对切半 → TTS 乱码/抛异常，且只在超长时出现，平时测不出来）
- [x] `TTS_MIN_GAP_MS=1500` 播报限流、`TASK_TTS_MAX_LEN=120`（远程任务文案放宽，旧实现完全无上限）

## 阶段3：素材工序（`tools/pet_audio/`）

- [x] `generate_sfx.py` 确定性合成 10 个 wav：numpy 加法谐波**限带**合成（朴素方波有混叠刺声）+ 每音升余弦淡入淡出（防爆音）+ 固定随机种子（可复现）+ 16bit PCM/44100/mono
- [x] 自检函数 `check_wav()` 逐条断言 §二 硬性规范；`--check DIR` 对已入库目录跑同一套规则
- [x] 实现文档《后处理流水线》第 1 步「裁剪头尾静音」= `trim_tail()`（只裁尾；裁头会切掉 t=0 的淡入产生爆音），裁完断言仍在 0.5–1.5s
- [x] 过程中修掉真实缺陷：9 个配方带 0.05–0.25s 尾部静音垫；2 个配方裁尾后越时长下限（改为延长末事件而非补静音）；`int()` 截断致总长少 1 样本（改 `round`）；校验器自身口径错误（"最后20ms峰值"会把合法淡出全判违规 → 改测首尾静音游程 ≤5ms/≤30ms）
- [x] 失败一次性报全（不在首个错误退出），台账 `out/_manifest.json` 记 declared/actual 时长+peak+rms+字节
- [x] 成品 10 张 656.6KB 入 `app-host/src/main/res/raw/`，`--check` 校验 0 异常

## 阶段4：受控端播放与播报（app-host）

- [x] `audio/RawSoundPlayer.kt`（文档 §九 MediaPlayer 封装）：soundId **显式 when 映射**到 `R.raw.*`（不用 `getIdentifier` 反射——release 包静默返回 0 = "没声音但日志干净"）；`USAGE_ALARM`/`USAGE_MEDIA` 双路由；单活跃实例 + 完成/出错双路径 release
- [x] 告警不被娱乐音抢占：告警在响时收到 pet_*/sound_ack → `Result.SKIPPED_BY_ALARM` 让路（不混音、不掐告警）
- [x] `audio/PetTtsGate.kt`（§九 TTS 工具类 + §七.3 启动检测）：进程级单例委托既有 `LocalTtsManager`；`Probe{UNKNOWN,READY,UNAVAILABLE}` 三态 + 10s 未回调判不可用（部分精简 ROM 永不回调 onInit）；READY 后锁定不被后续失败翻转（避免主控端提示文案来回跳）
- [x] `audio/PetAudioFeedback.kt`（§五 第 2、3 步）：音效先起、**朗读串在音效完成之后**（小喇叭上音话重叠会糊成一片）；音效失败仍尝试朗读（降级策略 2）；`Report.summary()` 输出"为什么没响/没说"进事件日志
- [x] 全部媒体/TTS 调用编组到主线程（`Handler(Looper.getMainLooper())`，手法与既有 `SirenManager` 一致）
- [x] `LocalTtsManager` 增 `onReady` 回调 + `isReady`：参数刻意加在 `onUnavailable` **之前**——现存调用用尾随 lambda，若追加到末位，"降级为文字气泡"会被静默绑成"就绪时执行"，语义完全反过来
- [x] 迁移既有调用点到门控：`PetMainActivity`（45 处 speak 不改名、只换实现；onDestroy 不再 release 单例）、`TaskPlayActionReceiver`（消除每次广播 new 引擎的泄漏）
- [x] `InkForegroundService`：分发 22/23；`handleInteractCore` 抽出为 31/23 唯一结算路径；`eventSemanticGuard`(eventId@triggerTs, TTL 10s) 跨类型去重；未知 ID 拒绝并回 `CODE_EXECUTION_ERROR`；hostReported 回声丢弃
- [x] 启动探测：`onCreate` 起门控 + probe 迁移时写日志并 `broadcastDeviceStatus()`
- [x] §六 受控端主动饥饿上报：30s PING 节拍内检查 `hunger<25`（与 `PetAiEngine` 同阈值，避免两套判据）+ **10 分钟冷却**（低饥饿是持续为真的区间，无冷却=每 30s 轰炸）+ 本地播 `pet_hungry` + 发 23
- [x] `DeviceStatusPayload.ttsAvailable: Boolean?`：探测期传 **null**（把"还没测出来"报成"不支持"会误显提示）

## 阶段5：主控端 UI（app-controller）

- [x] `audio/AudioSettings.kt`：全局【启用TTS文字播报】开关，落 `inklink_controller` SharedPreferences；**关闭时连 ttsText 都不发出**（少一个网络注入面 + 省流量）；默认开（受控端自判可用性并自动跳过，默认关会让新功能看起来没做）
- [x] `InkControllerApplication.sendPetEvent()` / `playHostSound()`：与既有 `sendRing/sendPushAlert` 同风格，返回 msgId
- [x] `PetDetailActivity` 新增互动音频区：🍖喂养 ✋抚摸 😊逗开心 💤哄睡 ☀️唤醒（23，带各自播报文案）+ 📢🔔⚠️✅ 系统提示音（22）+ 开关 + 能力提示行
- [x] 保留原 31 那批照料按钮不动（23 不替代 31：31 支持 count/foodType，如长按 5 连投）
- [x] 处理受控端 23 上报：`event_hungry_alert` → 弹窗「🍖 宠物饿了」+ 一键补喂（闭环回 event_feed）；`DEVICE_STATUS_REPORT.ttsAvailable==false` → 提示行显示"文字播报已自动关闭，预制音效不受影响"

## 阶段6：单测与 CI

- [x] `common/SoundProtocolTest`（14 用例）：码位不回退、两张白名单与 §二 逐字相等、`binding.soundId ⊆ RAW_NAMES`、**`action` 只能取 31 的既有取值域**（否则受控端 `when` else 会把新事件静默当抚摸）、`sleeping` 仅 SLEEP 可用、`hostReported` 必无 action、开关×文本×清洗组合、代理对不被切半、payload 往返 + 旧端缺字段走默认
- [x] `app-host/RawSoundContractTest`：`res/raw` 集合 **== RAW_NAMES**（不多不少）、手写 RIFF 解析验 16bit/mono/44100/时长/峰值/直流/首尾静音/削顶、`R.raw` 常量逐个存在
- [x] **变异测试证明门禁会红**：多一个合法非契约文件 → 集合断言 FAILED；篡改 fmt channels=2 → 规范断言 FAILED；恢复后全绿。附带发现 aapt 直接拒绝下划线开头资源名（第三层保险，但错误信息不可读，仍靠测试兜）
- [x] `:common:testDebugUnitTest` + `:app-host:testDebugUnitTest` + 双端 `compileDebugKotlin` 全绿
- [x] CI 绿 + Release 产物确认：run 33344601759(be8f08c) + 33344675687(f7881ef) 均 success，Release `v1.2.0-38` 双 APK

## 阶段7：降级场景矩阵（文档 §五/§八，可自动化部分）

| 场景 | 期望行为 | 现状 |
|---|---|---|
| 未知 soundId / eventId | 拒绝 + `CODE_EXECUTION_ERROR`，不兜底播放、不改数值 | [x] 单测 + 代码 |
| msgId 重复 | 幂等丢弃（`CODE_IDEMPOTENT_DROP`） | [x] 复用 IdempotentController + 16 例新单测（此前该类零测试） |
| 31 与 23 同一事件重投 | 语义幂等 collapse；用户连点因 triggerTs 不同不受影响 | [x] `IdempotentControllerTest#语义守卫键口径_重投吞_连点放` |
| `enableTts=false` / 文本空 / 全控制字符 | 只播音效，不发/不读文本 | [x] 单测 |
| 文本超长含 emoji | 码点边界裁 40 字，不留孤立代理 | [x] 单测 |
| 无 TTS 引擎 / 引擎损坏 | 跳过朗读、音效照常、主控端显示"不支持文字播报" | [x] 代码（真机待验） |
| wav 资产缺失/损坏 | `FAILED_NO_ASSET`/`FAILED_PLAY`，不抛异常、状态不回滚 | [x] 代码（CI 契约测试另挡缺失） |
| 告警响时收到娱乐音效 | `SKIPPED_BY_ALARM` 让路 | [x] 代码（真机待验） |
| 无配对主控端时的饥饿上报 | 本地提醒照发、不排队重放过期"我饿了" | [x] 代码 |
| APK 内 wav 被压缩导致播放失败 | 已实测：release 包内 10 个 wav 均未压缩（条目字节数与源文件相等） | [x] 已验 |
| 墙钟回拨致冷却/限流永久失效 | 普查命中 3 处并已修为单调时钟；16 例单测 + 变异测试 | [x] 已修 |
| 设备无扬声器 / 媒体音量为 0 | 不崩、状态照常，`Report.mediaMuted` 回显 | [x] 代码（真机待验） |
| TTS 播报高频轰炸 | 1.5s 限流跳过 | [x] `MonoThrottleTest`（窗口锚定上次放行/被拒不滑动/边界放行）；`PetTtsGate` 接线次序（bail 不占窗口）属真机项 |

## 阶段8：真机与联调（沙箱不可做）

- [ ] 手表端逐个试听 10 个音效（`adb push` 或直接装包后从主控端按钮触发），确认无爆音、响度不忽响忽轻（若忽响忽轻 → 上 LUFS 响度归一，见 B.6）
- [ ] 实测 `MediaPlayer` 短音效首帧延迟（文档按 §九 指定用 MediaPlayer，非 SoundPool；此项不做就只能定性说"有代价"）
- [ ] `alert_warn` 在音乐/静音键场景下仍可响（STREAM_ALARM 语义验证）
- [ ] 无 TTS 引擎的精简 ROM：确认只响音效 + 主控端正确显示"设备不支持文字播报"
- [ ] TTS 引擎首次初始化慢（>10s）的设备：确认不误报"不支持"、也不永久 UNKNOWN
- [ ] 局域网（WS）与 Ably 公网双通道联调：22/23 送达、ACK 回执、事件日志可读
- [ ] 弱网/断连恢复：饥饿上报不重复轰炸（10min 冷却）
- [ ] 内存/泄漏巡检：连续触发 100 次 23，`MediaPlayer` 实例数与 TTS Binder 不增长
- [x] 两个守卫已提纯为可注入时钟并补测：新增 `common/utils/MonoClock.kt`（MonoClock + MonoThrottle）
      + `IdempotentControllerTest`/`MonoThrottleTest` 共 16 例，含变异测试证据（design B.7）

## 阶段9：Git/CI 前全局复盘

- [x] 业务零破坏核对：31/32/40-46 既有链路行为不变（23 复用核心、不新增第二条数值路径）
- [x] 新增字段全部带默认值（Gson 旧端可读；`ttsAvailable` 用可空表达"未知"）
- [x] 无运行期反射查资源名、无 `!!`、可空判备完备
- [x] 冷却/限流/去重一律单调时钟，无新增墙钟窗口判据（普查见 design B.7）
- [x] 素材/协议/CHECKLIST 三处同源真值（`SoundProtocol.RAW_NAMES`）
## 阶段10：发现但**故意不在本次范围内**（交用户裁决，勿当已解决）

| # | 问题 | 证据 | 为何没顺手改 |
|---|---|---|---|
| 1 | 心跳 45s 超时判定用墙钟：`System.currentTimeMillis() - lastPongTs > 45_000L`，墙钟回拨 → 差值恒负 → **永远判不出掉线**（主控端显示在线而指令石沉大海） | `InkForegroundService.kt:451`（读写共 4 处：84/260/328/451，全为本地比较，不与对端时间戳相减） | 属连接保活语义，需真机验证"真掉线仍能判出且不误杀正常心跳"；沙箱无 adb。改动量小（4 行换时基），随时可做，等点头 |
| 2 | `app-controller` 测试**不在 CI 门禁内**：workflow 只跑 `:app-host:testDebugUnitTest` | `.github/workflows/*.yml` 第 32 行 | 且 `DeviceRepositoryTest.add_and_list` 是 order-dependent flaky（本地实测 `expected:<2> but was:<3>`，同代码重跑又绿），把它塞进 CI 前必须先修 flaky，否则门禁随机红 |
| 3 | `ReportThrottler` 用 `lastStatusTime == 0L` 当"从未上报"哨兵 + 墙钟 `now` 由调用方传入 | `ReportThrottler.kt:32,51` | 现在工作正常（墙钟下 0 哨兵成立）；但它是"将来迁单调时钟必踩"的地雷，已在 design B.7 留警示。定位上报与本特性无关 |
| 4 | `SoundEffectManager` 的 `soundPool` 疑似死资源 + `play()` 每音符 new Handler 不取消 | 阶段2 存量盘点 | 与 `RawSoundPlayer` 并存不合并（合并要重测 chiptune 全部类目），留待独立工单 |

- [ ] 本清单全勾 + design 附录B 与实现一致（三遍复盘：① 逐行 diff 查出 `lastSkipReason` KDoc 与实现不符并已修 ② 文档铁律 §五/§八 与 android-lead 三条对齐无破坏 ③ 回归面 = IdempotentController 2 个调用点 + 默认时基不外泄到 payload/DB，已核）
- [x] commit 拆分清晰，9 个各一类：`1e551db` 合成工序+资产 / `ae3e126` 受控端播放层 / `c7aec97` 受控端接入 / `05d161c` 主控端 / `be8f08c`+`f7881ef` 文档 / `a2a36f6` 协议层 / `37f89c9`+`0914add` 时钟缺陷修复 / `e08ecc5` 文档。
      过程中的教训已记：一次 bash 里嵌 3 个 heredoc 把 message 弄坏（标题混进 `MSG`），因未 push 用 `git reset` + `commit -F 文件` 重建，并验证重建前后 `git diff` 为空
- [x] 全部已 push（origin/master 同步、工作区干净）。CI：run 33344601759 / 33344675687 / 33345936074 均 success，Release 链 `v1.2.0-37/38/39` 双 APK
