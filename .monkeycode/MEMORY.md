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
  - CI 失败排查路径：`curl -sL -H "Authorization: token $TOKEN" https://api.github.com/repos/caogenfunan123/inklink-study/actions/runs/<run_id>/logs` 下载 zip 解压后 rg "e: |FAILED" build/5_*.txt（编译错误集中在 :app-host:kspDebugKotlin / :app-controller:compileDebugKotlin）（2026-10-03）
  - 编辑器对大文件（千行级 Kotlin）做 edit 替换时可能残留旧代码片段导致语法错误；push 前必须回读修改段落复查（2026-10-03 CI 连败两次的根因之一）

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


[QQPet vendor 终局口径（公开 + 全量素材）]
- Date: 2026-10-13
- Context: Discovered by Agent while 处理 Actions artifact 配额打满与仓库可见性决策
- Category: Operations & Deployment / Environment Configuration
- Instructions:
  - inklink-study 最终为 **public**：私有仓库 artifact/release 存储计入 Free 500MB 账户配额（已因 21 个旧 Release×86MB≈1.8GB 打满）；公开仓库 Actions 免费不计该配额。转公开即解锁 CI 产物上传
  - 用户明确选择公开全量：GPL-3.0 代码公开合规（LICENSE 随仓）；**腾讯素材（resources/pet）由用户自行决定公开再分发，风险自负**；InkLink Kotlin 代码不受 GPL 传染（独立模块独立 APK，仅同仓共存）
  - LFS 素材 329MB 由 **CI 代劳入库**：本沙盒出网限速 ~12KB/s（多连接并发无效），8 小时以上不可行；改为 `.github/workflows/assets-import.yml`（workflow_dispatch，GITHUB_TOKEN contents:write）在 runner 上固定克隆上游 db7cf74 + git lfs pull → 拷入 third_party/qqpet/resources/ → 提交推送，内置文件数/指针守卫、无变更跳过；用户本地推送为备用（流程见 third_party/qqpet/VENDORED.md）。素材已于 2026-10-13 入库（提交 966a6399）
  - 发版触发改为仅 `v*` tag push 或 workflow_dispatch（普通 push 只跑单测+构建，不建 Release）；tag_name 用 github.ref_name，dispatch 用 v1.2.0-<run_number>
  - 2026-10-13 合并进 app-host 后 CI 改为两 job（build/release）：build job 内完成 LFS pull + 素材就位守卫（缺失硬失败）+ Node22 npm ci/typecheck/build:android-web + 单测 + assembleRelease；原 qqpet job 退役，独立 net.ankio.qqpet APK 不再构建；release 只下载 inklink-apks
  - .gitignore 坑仍在：`**/android/gradlew`、`*.jar`、`**/build/` 误杀 vendor 文件，靠根 .gitignore 否定规则兜住；新增 vendor 目录先跑 `find third_party -type f | git check-ignore --stdin` 排查

[QQPet 游戏厅合并（app-host 内嵌）]
- Date: 2026-10-13
- Context: Discovered by Agent while 将 vendor qqpet 游戏厅合并进 app-host 并改造 CI
- Category: Build Methods / Workflow & Collaboration
- Instructions:
  - 移植落点 `app-host/src/main/java/com/inklink/host/qqpet/`：QQPetAssetClient（1:1 移植上游 AssetClient）/ QQPetActivity（index.html 大厅 + window.QQPetNative 全桥）/ QQPetGameActivity（game.html 全屏）；上游壳 PetService/BootReceiver/MainActivity/TextActivity 悬浮窗生态刻意不移植，四方法打桩
  - 桥契约唯一定义在 `third_party/qqpet/src/android/bridge.ts`（14 方法，`__qqpet.resolve` 回填）；改契约必须同步 Kotlin 实现与 `.monkeycode/docs/模块/qqpet-游戏厅.md` 契约表
  - `gamePlayed` 分钟上报必须保留：QQPetGameActivity 结束时经 QQPetActivity.gamePlayedListener 对大厅页 `__qqpet.emit('gamePlayed', n)`，游戏厅内每日元宝/心情/成长奖励靠它结算（漏掉奖励永不触发）
  - 本地构建 app-host 前必须 `git lfs pull` + `cd third_party/qqpet && npm run build:android-web`，否则 assets 缺 vite 产物/素材
  - app-host 资产口径：assets.srcDirs 注入 `third_party/qqpet/out/android` + `third_party/qqpet/resources`；noCompress += swf/png/gif/jpg/mp3/wasm；out/android 被 vendor .gitignore 的 out/ 忽略，不入库由 CI 重建
  - push 遇 non-fast-forward 先 fetch 看分叉：assets-import 工作流会自行推提交；本地未跟踪的 third_party/qqpet/resources/（真实素材）会挡住 rebase，先 `mv` 到 /tmp 再 rebase；LFS oid 校验用 `git cat-file -p :<path>` 取指针内 oid sha256，`git ls-files -s` 取到的是指针文件 git 哈希（会误判）
