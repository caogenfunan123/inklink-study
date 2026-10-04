# InkLink 开发者指南

> 环境搭建、构建测试、CI、常见任务与架构红线。配合 `ARCHITECTURE.md`（全局）与 `INTERFACES.md`（协议）使用。

## 1. 项目定位

InkLink 是装在儿童平板/手表上的「电子宠物 + 幼小衔接课程」双端 App：孩子端离线自学赚金币养宠物，家长端远程关怀、管控与安全。零服务器硬约束：学习内容全离线内置，设备间通信走局域网 WS 或 Ably 云中转，无任何自建后端。

**核心职责**:
- app-host（孩子端）：宠物养成 / 学习乐园 / 投屏 / GPS 围栏 / 语音对讲 / 聊天 / 保活
- app-controller（家长端）：设备管理 / 地图围栏 / 宠物远程关怀 / 强控指令 / 告警 / 聊天
- common：协议 / 传输 / 音频 / 定位 / 工具（双端唯一共享层）

## 2. 环境搭建

### 前置条件

- JDK 17（temurin）
- Android SDK 34：platforms;android-34 + build-tools;34.0.0
- Gradle 8.5：仓库自带 wrapper（`./gradlew`），无需手动安装
- Python 3 + numpy/Pillow（仅重生成素材时需要）

### 安装

```bash
git clone https://github.com/caogenfunan123/inklink-study.git
cd inklink-study
```

创建 `local.properties`（仓库根，不入库）：

```properties
sdk.dir=/opt/android-sdk
ABLY_KEY=your-ably-key
TENCENT_MAP_KEY=your-tencent-map-key
TENCENT_MAP_SK=your-tencent-map-sk
```

- 密钥经 build.gradle.kts 注入 BuildConfig/manifestPlaceholders，源码与 CI 属性均不落库
- Ably Key 空串时 Ably 模式不可用，可先用 LOCAL 局域网模式开发
- 腾讯地图 Key 与包名+SHA1 绑定，换签名需在腾讯控制台重新登记

### 仓库源说明

settings.gradle.kts 已配置阿里云镜像（maven.aliyun.com 的 central/google/gradle-plugin）置前 + 腾讯 maven 源（`mirrors.tencent.com/nexus/repository/maven-public/`）。直连 Maven Central 在受限网络会 403，保持镜像配置勿删。

## 3. 构建与运行

### 常用命令

```bash
./gradlew :common:testDebugUnitTest :app-controller:testDebugUnitTest :app-host:testDebugUnitTest --console=plain
./gradlew :app-host:assembleDebug :app-controller:assembleDebug
./gradlew assembleRelease
```

### 产物

| 产物 | 路径 |
|------|------|
| 孩子端 APK | app-host/build/outputs/apk/debug/app-host-debug.apk |
| 家长端 APK | app-controller/build/outputs/apk/debug/app-controller-debug.apk |
| Release（未签名） | 同目录 release/，CI 重命名为 InkLink-Host-release.apk / InkLink-Controller-release.apk |

### 安装运行

```bash
adb install -r app-host/build/outputs/apk/debug/app-host-debug.apk
adb install -r app-controller/build/outputs/apk/debug/app-controller-debug.apk
```

双机联调最小路径：两台设备连同一 WiFi → 孩子端 HostActivity 待机页出示二维码（ws://ip:8080）→ 家长端扫码连接（传输模式切 LOCAL）。跨网联调用 ABLY 模式（默认），双端填同一 pairingKey。

### CI（.github/workflows/android.yml）

- 触发：push master/main + 手动 workflow_dispatch
- 步骤：JDK 17 → `:app-host:testDebugUnitTest` 门禁（失败上传报告）→ `assembleRelease`
- 密钥：GitHub Secrets `ORG_GRADLE_PROJECT_ABLY_KEY` / `TENCENT_MAP_KEY` / `TENCENT_MAP_SK`
- 发布：softprops/action-gh-release，tag `v1.2.0-<run_number>`，未签名 APK
- 注意：app-controller 单测目前不在 CI 门禁内

## 4. 开发工作流

### 提交惯例

参考现有历史：`feat(M2): ...` / `fix: ...` / `chore: ...` / `docs: ...`，中文描述，一行说清改动与原因。资产类提交用 `chore: 素材(...)` 注明批次。

### 分支与发布

- master/main 直推（个人项目惯例），CI 随推随构建
- Release 由 tag push 或 master push 触发

### 代码质量工具

| 检查 | 命令 |
|------|------|
| 全量单测 | `./gradlew test` |
| 单模块单测 | `./gradlew :app-host:testDebugUnitTest` |
| 构建验证 | `./gradlew assembleDebug` |

无独立 lint/typecheck 脚本，Kotlin 编译即类型检查；改素材后必跑对应契约测试（见第 5 节）。

## 5. 常见任务

### 新增消息码位

**需修改的文件**:
1. `common/.../protocol/MessageType.kt` - 新增枚举（先查 INTERFACES.md 第 2 节确认码位空闲）
2. `common/.../protocol/payload/` - 新载荷数据类 + round-trip 单测
3. `app-host/.../service/InkForegroundService.kt` - 受控端 when 分支
4. `app-controller/.../InkControllerApplication.kt` - 主控端 route() 分支
5. `.monkeycode/docs/INTERFACES.md` + `docs/IMPLEMENTATION_PLAN.md` 附 B - 登记

**步骤**: 登记码位 → 写载荷与单测 → 双端路由 → 若走好友频道更新 AblyRelayTransport 白名单并注释 → 同步文档。enum 重复码位会使 fromCode 静默覆盖，MessageTypeTest 会拦，但别依赖测试兜底。

### 新增学习模块（参考 M2 三岛）

1. `app-host/.../learning/LearningManager.kt` - 出题方法（返回 entries）+ module 常量
2. `app-host/.../ui/learning/` - 新原生 Activity（参考 MathActivity 最简形态）
3. `LearningHubActivity` + `LessonRouter` - 入口与路由
4. `common/.../payload/LearnPayloads.kt` - module 枚举加值（47 载荷 module 字段）
5. 若需资产：在 `tools/learning/build_learning_assets.py` 增转换逻辑并重跑
6. 主控端 `handleLearnProgress` 的 module 中文化映射同步

**注意**: 结算必须走 `LearningManager.finishLesson`（内部唯一走 `PetStateManager.addReward`），UI 不直接写 learn_progress/wrong_book/daily_stats 三表。

### 新增宠物物种

1. `app-host/.../ui/view/PixelSpecies.kt` - 程序化像素调色板与三件套槽位（回退兜底必配）
2. 精灵帧（可选）：tools/pet_sprite 管线出图 → `res/drawable-nodpi/`；命名 `{species}_{state}.png` 96x96 8bit PNG 质心 (48,48)
3. 街机帧（可选）：`assets/pixel_arcade/{species}/` 12 张 `{species}_{idle|hungry|happy|sleep}_{01..03}.png` 256x256
4. `state/PetSpriteResMap.kt` / `PetArcadeMap.kt` - 资源映射；素材缺失走三级回退（PIXEL_PNG → arcade → 程序化）
5. 跑 `PetSpriteAssetContractTest` / `PetArcadeAssetContractTest` 验契约

### 新增音效

1. `tools/pet_audio/generate_sfx.py` 加配方（硬性规范：16bit/44100Hz/单声道/0.5-1.5s/峰值-1dBFS）
2. `python tools/pet_audio/generate_sfx.py --deploy` 落 res/raw
3. `common/.../payload/SoundPayloads.kt` - RAW_NAMES/绑定表登记
4. `RawSoundContractTest` 会校验文件集合与 RAW_NAMES 严格相等

### 重生成学习资产

```bash
python tools/learning/build_learning_assets.py
```

依赖同级目录 ref-hanzi-study / ref-joye-preschool / ref-studyword 三个参考仓库（环境变量可覆盖路径）。**注意**：脚本会重新生成已退役的 `lib/strokes.json` 与 `lib/hanzi-writer.min.js`（WebView 时代产物），重跑后应剔除 lib/ 目录，保持 `assets/learning/` 只有 6 个 JSON。

### 修复 Bug 流程

1. 定位归属：业务结算在 InkForegroundService/PetStateManager/LearningManager；UI 层只有展示与事件
2. 涉及时序/冷却/去重的 bug，先怀疑墙钟：统一用 MonoClock/elapsedRealtime，MonoThrottleTest 有时钟回拨回归样例可参照
3. 修复后补单测钉死（项目惯例：历史故障模式用测试锁住，见 IdempotentControllerTest）

## 6. 架构红线（改前必读）

1. **单一真相源**：业务状态只允许在 InkForegroundService / PetStateManager / LearningManager 修改；UI 只订阅 SharedFlow。
2. **奖励唯一入口**：一切金币/经验只走 `PetStateManager.addReward`。
3. **防双扣**：31/23 远程互动必须汇入 `handleInteractCore`；msgId 幂等去重勿绕过。
4. **码位不可改**：已发布协议码位只增不改；enum 禁止重复 code。
5. **零服务器**：不引入任何自建后端依赖；新在线功能必须标注降级路径与离线行为。
6. **密钥不入库**：Key 只进 local.properties / CI Secrets；代码与文档中只允许占位符。
7. **学习/宠物计时防作弊**：统一 `SystemClock.elapsedRealtime()` / MonoClock，禁用 System.currentTimeMillis 做时长与冷却。
8. **音频通道**：宠物音效/TTS 绑 STREAM_MUSIC，告警绑 STREAM_ALARM 并可打断 TTS；勿混用。
9. **UI 适配**：ConstraintLayout + dp/sp，禁固定 px；低内存设备（<480dp）资源降采样逻辑勿破坏。
10. **协议单真值源**：SoundProtocol 白名单、MessageType 唯一在 common；双端禁止私自定义码位或音效名。
11. **主线程 Room**：app-host PetDatabase 允许主线程查询是既有取舍（简单优先），新表写操作建议仍走单例协程，勿扩散 allowMainThreadQueries 用法。

## 7. 编码规范

- 语言：Kotlin 100%，View 体系（非 Compose），Material3 主题 `Theme.InkLink`
- 命名：类 PascalCase、函数/变量 camelCase、常量 SCREAMING_SNAKE；管理器类统一 `XxxManager` 单例风格（companion object + 全局锁，参考 PetStateManager/LearningManager）
- 注释：中文，重点写「为什么」与历史教训（如防双扣、码位冲突注释）
- 消息处理：受控端所有指令先 `acquireTemporaryWakeLock(2s)` 再分发；回复走 CMD_ACK 带 ackMsgId
- 测试：JUnit4 + Robolectric（`testOptions.unitTests.isIncludeAndroidResources = true`）；资产契约测试用手写 PNG/WAV 解析器，不引第三方图像库
- 资源：像素素材入 drawable-nodpi（禁缩放）；音频入 res/raw（名与 RAW_NAMES 契约）

## 8. 已知坑与待办（接手开发先看）

| 事项 | 位置/说明 |
|------|----------|
| M3 未开工 | 48/49 作业协议已定义，主控端零消费；学习报告仅内存一行简报。开发入口：app-controller 新增 HomeworkActivity/LearnReportActivity + Room 或文件持久化 |
| 英语岛未接线 | english_words.json/english_sentences.json 已在 assets，LearningHubActivity 入口显示「建设中」 |
| 笔顺描红后置 | WebView 链路已退役，hanzi-writer.min.js 已删；后续若恢复需重做原生描红或 reintroduce WebView |
| build_learning_assets.py 副作用 | 重跑会生成已退役的 lib/ 目录，需手动剔除 |
| SoundEffectManager 遗留缺陷 | CHECKLIST_PET_AUDIO 阶段1末条，待修 |
| 真机项未验证 | CHECKLIST_PET_AUDIO 阶段8（STREAM_ALARM 实测/弱网/内存巡检等 8 条）、CHECKLIST_PET_SPRITE 阶段6 |
| app-controller 测试不在 CI | 已不成立：CI 现跑 common/host/controller 三模块单测 |
| sheep 街机素材缺失 | PetArcadeMap 三级回退兜底，勿删回退逻辑 |
| Ably ConnectionState 包名 | 用 `io.ably.lib.realtime.ConnectionState`（非 types 包） |
| 答题三件套易漏 | 2026-10 复盘：识字/拼音两岛错答分支曾锁死课程。新答题页先抄 MathActivity（锁输入+高亮正解+推进索引） |
| CI 必须含 :common | 2026-10 复盘：GeoFenceManagerTest 多围栏用例因 common 测试不进 CI 而长期隐形失效，已补门禁 |
| 墙钟禁令 | 冷却/限流/去重窗口一律 MonoClock（SystemClock.elapsedRealtime），墙钟回摆功能静默失效 |
| 双时钟 settle 顺序 | 翻转 PetClock.foreground 前必须先按当前倍率 settle；onResume 顺序反过（曾整段后台按前台结算） |
| 配对密钥两端一致 | 默认 inklink_default_key 可预测；两端在各自设置页改，改一端必须同步另一端，否则 ABLY 频道失联 |
| 异步弹窗守卫 | runOnUiThread 后弹 AlertDialog 前必须 isDestroyed/isFinishing 双检，否则关页崩溃（BadTokenException） |
| 学习模块无单测 | LearningManager/艾宾浩斯/护眼规则仅靠代码注释约束，改前仔细读 LearningManager 内注释 |
| 金币经济参数 | 日上限 150 / 刷关递减 24h 内第 3 次减半第 5 次为 0 等口径见 docs/IMPLEMENTATION_PLAN.md 第 6 节，改数值需对齐该文档 |
