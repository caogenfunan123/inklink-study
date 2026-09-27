# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[构建验证走 GitHub Actions CI（本地无工具链）]
- Date: 2026-09-27
- Context: 用户在本地沙盒重置（无 JDK/SDK）后明确指示不走本地构建
- Category: Workflow & Collaboration / Environment Configuration
- Instructions:
  - 代码验证优先推送 GitHub 仓库后走 Actions CI（`.github/workflows/android.yml`，push master 自动触发：双模块单测 + assembleRelease + Release 发布），不在本地装 JDK/Android SDK 做构建
  - 用户会提供 GitHub PAT（聊天中给出）；token 只允许写入 /tmp 临时文件使用（chmod 600），严禁写入仓库任何文件或 MEMORY；用完建议用户轮换
  - 推送输出必须 sed 脱敏 token；git push 形式 `git push https://x-access-token:${TOKEN}@github.com/caogenfunan123/inklink-study.git master:master`
  - CI 单测门禁覆盖 :app-host 与 :app-controller 两模块（2026-09-27 起含 TrackStore 等新用例）

[InkLink 仓库生态与签名体系]
- Date: 2026-09-27
- Context: Discovered by Agent while 排查 CI 产物不可安装/无密钥问题并建立仓库关联
- Category: Operations & Deployment / Environment Configuration
- Instructions:
  - 五仓拓扑：inklink-study（master，开发主线 monorepo 全功能）+ inklink-common/controller/host（main，2026-08-28 拆分的发布载体，common 走 submodule@09a3e81 已落后主线）+ inklink-app（早期统一 APK 实验，停滞）
  - 统一签名：keystore/inklink-release.keystore（alias inklink，密码 InkLink@2026_Release_Store 已在拆分仓 SIGNING.md 公开），SHA1=2E:60:B8:89:B0:6D:07:21:E9:0F:01:9B:2A:DE:0E:A7:89:7B:9B:AD（腾讯控制台登记用）；双模块 signingConfigs 从 INKLINK_* env/local.properties 读取
  - study 仓 CI secrets 已配齐（2026-09-27 经 API 写入）：ABLY_KEY（值取自拆分仓硬编码默认值）、INKLINK_STORE_PASSWORD/KEY_PASSWORD/KEY_ALIAS
  - 拆分仓哲学是密钥硬编码默认值开箱即用；study 主线口径是密钥不入库（local.properties/CI secrets），两口径并存，study 改动勿引入硬编码 key
  - 仓库关联总览文档：/workspace/REPOSITORIES.md（拓扑/同步矩阵/操作指引），controller 仓 README 有回链

[InkLink 构建环境与命令]
- Date: 2026-08-28（2026-09-27 更新）
- Context: Discovered by Agent while 对 InkLink Android 三模块工程做编译验证；2026-09-27 拉取 inklink-study 仓库后复核
- Category: Build Methods & Environment Configuration
- Instructions:
  - 构建命令：`cd /workspace && ANDROID_HOME=/opt/android-sdk ./gradlew :common:testDebugUnitTest :app-controller:testDebugUnitTest :app-controller:assembleDebug :app-host:assembleDebug --console=plain`
  - 本机需手动安装：openjdk-17-jdk-headless、Android SDK 34（/opt/android-sdk，含 platforms;android-34 与 build-tools;34.0.0）
  - 仓库现自带 gradle wrapper（gradle-8.5，gradlew/gradle-wrapper.properties 已入库），直接 `./gradlew` 即可；旧 monorepo 无 wrapper 需系统 Gradle 的历史情况已失效
  - `/workspace/local.properties` 需写 `sdk.dir=/opt/android-sdk` 指向 SDK
  - 网络：repo.maven.apache.org（Maven Central 直连）在本环境返回 403，依赖解析必须走阿里云镜像（maven.aliyun.com/repository/central、/google、/public），已配置于 settings.gradle.kts 并置于最前
  - 地图 SDK 已从高德迁移为腾讯地图（仅 app-controller）：Maven 源 `https://mirrors.tencent.com/nexus/repository/maven-public/`，依赖 `tencent-map-vector-sdk:6.13.0.260731.bb0666d5.209828299` + `foundation:0.9.1.6875646`；Manifest meta-data 配 Key；隐私初始化 `TencentMapInitializer.setAgreePrivacy` → `start`
  - Ably 依赖 `io.ably:ably-android:1.2.52` 进 common；Key 经 `/workspace/local.properties` 的 `ABLY_KEY` → 两端 build.gradle.kts 注入 `BuildConfig.ABLY_KEY`；Ably 连接状态枚举是 `io.ably.lib.realtime.ConnectionState`（非 `io.ably.lib.types.ConnectionState`）
  - 产物：`app-host/build/outputs/apk/debug/app-host-debug.apk`、`app-controller/build/outputs/apk/debug/app-controller-debug.apk`

[InkLink-Pet 架构与开发规范]
- Date: 2026-08-30
- Context: Discovered by Agent while 确立 InkLink-Pet 电子宠物与管控后台双重形态设计
- Category: Workflow & Collaboration
- Instructions:
  - 架构真相源：`InkForegroundService` 为全业务唯一真相源，UI 仅单向订阅 MutableSharedFlow 展示，禁止 UI 直接修改业务数据
  - 动画与交互：普通彩色安卓机型（非墨水屏），引入 `game-juice`（触觉/弹簧/粒子）、`ui-animation`（60-120fps/生命周期停控）、`beautiful-ui`（Material3/磨砂HUD）、`android-lead`（安全与架构防线）与 `code-debug-review`（代码复盘/根因排查/时序竞态）五项规范
  - 音频与安全：宠物互动音效/TTS 必须绑定 `STREAM_MUSIC`，强控告警必须绑定 `STREAM_ALARM` 并强行打断 TTS；高危指令校验 HMAC-SHA256 与 +-60s 防重放时间窗
  - 规范基准文档：详细设计与任务拆解见 `.monkeycode/specs/pet-system/design.md`

