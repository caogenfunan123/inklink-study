# QQPet 游戏厅合并复盘（2026-10-13）

> 范围：原生单机小游戏下线 → LFS 素材 CI 入库 → vendor qqpet 游戏厅合并进 app-host → CI 改造。
> 结论先行：全链路已交付并推送，三份关键提交 ff5f9ae（下线）/ 202848d + 966a6399（素材）/ 3e6de7b7（合并），
> CI run 38361388257（素材就位后三 APK 全绿）。唯一未闭环项：Ruffle 真机性能未实测。

## 1. 交付清单

| 阶段 | 提交 | 内容 | 验证 |
|------|------|------|------|
| 游戏下线 | ff5f9ae | 删 game/PetMiniGameManager、PetArcadeMap、街机资产契约、SpinWheelView、tools/pet_arcade、assets/pixel_arcade（240 文件）；PetMainActivity 2225→1918 行；PetSpriteView 1519→1462 行；PIXEL_ARCADE_SPRITE 枚举值移除；外观菜单 5→4 项；六处文档同步 | CI 38355519040 success |
| 素材入库 | 202848d + 966a6399 | 新建 assets-import.yml 工作流；run 38360926303 success，347MB / 8059 文件落 third_party/qqpet/resources/ | 守卫全过（文件数/指针抽样/幂等） |
| 游戏厅合并 | 3e6de7b7 | qqpet/ 三文件 373 行；manifest 注册；btnGames 接线；build.gradle sourceSets+noCompress+版本 2/0.2.0；CI 改两 job | CI 38366560485 build success（release 按设计跳过） |

## 2. 关键决策记录

| 决策 | 理由 |
|------|------|
| SpinWheelView 一并删除 | 复审确认它是「幸运转盘」小游戏之一，非独立轻互动；游戏面清零必须删 |
| PIXEL_ARCADE_SPRITE 枚举直接删 | 持久化按名解析、未知值回退 PIXEL_PNG，老存档不炸；保留枚举只会养死代码 |
| 素材走 CI 入库而非本地推送 | 沙盒出网 ~12KB/s，347MB 本地推不可行；runner 克隆上游 + GITHUB_TOKEN 提交，可重复、有守卫 |
| 只移植全屏 Activity 生态 | PetService 悬浮窗/BootReceiver/MainActivity/TextActivity 与 InkForegroundService 打架，四桥方法打桩 |
| minSdk 23 兼容写法院 | WindowInsetsControllerCompat 替代上游 insetsController!!（上游在 API<30 会 NPE）；safeArea 用 R 守卫 |
| save.json 落 app-host filesDir | 与 InkLink 宠物状态（SP/Room）零交集；QQPet 宠物是「游戏厅宠物」，正主仍是 InkLink 宠物 |
| 独立 QQPet APK 退役 | 单一 App 定位（孩子设备只装一个）；CI 从三 job 简化为两 job，LFS 守卫从「跳过」升级为「硬失败」 |

## 3. 移植保真度核查（防静默降级）

| 上游件 | 行数 | 移植件 | 结论 |
|--------|------|--------|------|
| AssetClient.kt | 97 | QQPetAssetClient.kt 112 | 1:1 忠实（仅改名+KDoc+公开化）； petsoc 大小写映射/高清 SVG/viewport 注入全在 |
| GameActivity.kt | 73 | QQPetGameActivity.kt 60 | 砍悬浮窗显隐（无生态）；**补回 gamePlayed 上报**（见下） |
| bridge.ts 契约 | 14 方法 | Bridge 内类 14 方法 | 签名逐一核对一致；setTray/setBounds 的 null/Double 语义对齐 |
| gamePlayed 事件 | GameActivity emit | gamePlayedListener 分发 | 见 §4 |

## 4. 复审抓出的真问题（已修）

- **gamePlayed 断链**：初版移植把上游 `emit("gamePlayed", minutes)` 一并砍掉，复盘时发现
  games.ts:17 与 community.ts:68 靠它结算游戏厅内每日奖励（元宝 5/分钟、心情 +2/分钟、成长，日上限 60 分钟），
  漏掉奖励将永不触发且无任何报错。修复：QQPetGameActivity 结束时经
  `QQPetActivity.gamePlayedListener` 对大厅页 `__qqpet.emit('gamePlayed', n)`；返回大厅（非 finish）不上报。
- 教训：移植「壳」类代码时，`emit`/回调/监听三条线要逐条查消费方，不能只看 Kotlin 侧看起来多余就砍。

## 5. 踩坑记录

| 坑 | 现象 | 处理 |
|----|------|------|
| 未跟踪素材挡 rebase | push 被拒后 rebase 报 could not detach HEAD | assets-import 自行推了 966a6399 造成分叉；本地 third_party/qqpet/resources/（347MB 真实素材）未跟踪，rebase 前 mv 到 /tmp 再 rebase |
| LFS oid 校验误判 | `git ls-files -s` 取到的哈希与文件 sha256 不一致 | ls-files 给的是指针文件的 git 对象哈希；LFS oid 要从 `git cat-file -p :<path>` 指针内取 oid sha256 |
| 上游行数记忆偏差 | 记录 AssetClient 215 行 / GameActivity 145 行 | 实际 97/73——以文件为准，复盘前必须重新读源比对 |

## 6. 风险台账（未闭环）

| # | 风险 | 等级 | 下一步 |
|---|------|------|--------|
| R1 | Ruffle wasm 播放器在中低端机型帧率未知 | **高** | 孩子设备真机安装 InkLink-Host 跑 3 个 SWF；卡顿则降级为图片/短视频替代或限帧 |
| R2 | APK 约 360MB（素材全量内置） | 中 | 已接受；若安装受阻再议资源分包或按需下载 |
| R3 | qqpet 包零单测 | 中 | 虚拟 origin 路径映射（petsoc 大小写/viewport 注入）值得补 JVM 单测，需要 Instrumented 或抽纯函数 |
| R4 | 游戏厅内网络能力（天气/AI/更新检查经 http 桥出网） | 低 | 上游既有功能；家庭场景默认放行，如需断网可用系统权限收口 |
| R5 | save.json 卸载即失 | 低 | 设计使然；SAF 导入导出为唯一迁移手段，需在孩子设备首次引导时口头告知 |

## 7. 后续入口

- 真机验收：装 InkLink-Host-release.apk → 游戏按钮 → 大厅加载 → 任选小游戏 → 退出看分钟奖励
- 契约变更：改 `third_party/qqpet/src/android/bridge.ts` 必须同步 `qqpet/QQPetActivity.kt` 与 `模块/qqpet-游戏厅.md` 契约表
- 素材更新：跑 assets-import 工作流（入库后 build job 自动带上）
- 文档基线：`模块/qqpet-游戏厅.md`（设计）/ `third_party/qqpet/VENDORED.md`（provenance）/ 本文件（决策与风险）
