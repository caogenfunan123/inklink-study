# QQPet 游戏厅（app-host 内嵌）

孩子端唯一的游戏内容源：vendor 自 AnkioTomas/qqpet 的 Web 复刻版 QQ 宠物，以 WebView + Ruffle 全屏 Activity 形态合并进 app-host。上游含腾讯 QQ 宠物美术素材（LFS，347MB / 8059 文件），仅限个人学习，禁止分发。

## 定位与边界

- 入口：宠物主界面五按钮之「游戏」按钮 → `QQPetActivity`（竖屏大厅 index.html）→ `QQPetGameActivity`（横屏全屏 game.html?swf=xxx）
- 2026-10-13 起，app-host 原生单机小游戏（猜拳/翻牌/转盘）与街机渲染模式全部下线，游戏内容仅此一厅
- 与 InkLink 宠物系统完全隔离：存档、经济、状态零耦合；游戏厅内元宝/成长不流入 `PetStateManager.addReward`
- 上游壳的悬浮窗生态刻意不移植（见下文清单）

## 源码结构

```
app-host/src/main/java/com/inklink/host/qqpet/
├── QQPetAssetClient.kt    # 112行 虚拟 origin 资产 Serve（1:1 移植上游 AssetClient）
├── QQPetActivity.kt       # 211行 游戏厅主页 + window.QQPetNative 全桥 + gamePlayed 上报
└── QQPetGameActivity.kt   # 60行 单个小游戏全屏播放（横屏沉浸式）
```

上游参照（勿直接改，改动需同步评估移植）：`third_party/qqpet/android/app/src/main/java/net/ankio/qqpet/`（AssetClient 97 行 / GameActivity 73 行 / PetService 390 行）

## 资产 Serve 机制

全部游戏资源打在 APK assets 内，运行时由 WebViewClient 从虚拟 origin Serve，页面本身零网络依赖：

- origin：`https://appassets.androidplatform.net`（Android 通用占位域名，WebView 视为 https，允许 fetch/wasm）
- assets 数据源（`app-host/build.gradle.kts` 的 `androidResources`/`sourceSets` 注入两路）：
  - `third_party/qqpet/out/android/` —— vite 产物（index/game/swf.html + ruffle/），CI 现构建，不入库
  - `third_party/qqpet/resources/` —— 上游 LFS 素材（pet/、icons/），已入库
- SWF 路径：URL 按 `/` 切分过滤空段后，`pet/petsoc/` 下的段做大小写映射（上游 PetSoc 是 Windows 转储，配置写反斜杠路径、资产目录大小写不统一）
- index.html 注入 viewport：按屏幕短边与密度换算 initial-scale，游戏厅铺满且不缩放糊
- 高清档：带 `@2x.png` 孪生图的图片按 SVG 内嵌 Serve（读 PNG 头取宽高 ÷2），省 assets 体积
- 媒体文件（swf/png/gif/jpg/mp3/wasm）走 `noCompress` 直打 APK，免二次压缩

## 桥接口契约（window.QQPetNative）

上游契约唯一定义在 `third_party/qqpet/src/android/bridge.ts`，本模块为合并后唯一实现方。异步调用经 `__qqpet.resolve(id, value, error)` 回填；`__qqpet.emit` 为壳向页面推事件。

| 方法 | 行为 | 移植状态 |
|------|------|---------|
| read(name) | 读 filesDir 文件文本，缺失返回 null（桥转 undefined） | 完整 |
| write(name, text) | 原子写（.tmp + rename） | 完整 |
| http(id, method, url, headers, body) | 线程池代理 fetch，回填 `{status, body}` | 完整 |
| exportSave(id, name, text) | SAF ACTION_CREATE_DOCUMENT 导出 | 完整（Activity Result API） |
| importSave(id) | SAF ACTION_OPEN_DOCUMENT 导入 | 完整（Activity Result API） |
| openGame(swf) | 拉起 QQPetGameActivity | 完整 |
| quit() | finish 当前页（上游语义是退出桌面宠） | 完整 |
| copyText(text) | 剪贴板 | 完整 |
| openUrl(url) | 浏览器 Intent | 完整 |
| setFocusable(on) | 悬浮窗焦点 | **打桩**（悬浮窗不移植） |
| setAutoStart(on) | 开机自启 | **打桩**（开机自启由 InkLink 保活体系负责） |
| setTray(icon, tip) | 托盘通知 | **打桩** |
| setBounds(x,y,w,h,viewport) | 悬浮窗矩形 | **打桩** |
| safeArea(viewport) | 系统栏+刘海安全区（CSS px） | 完整（API 30+ 实现，minSdk 23 有 R 守卫） |

事件方向（壳 → 页面）：`__qqpet.emit('gamePlayed', minutes)` 由 `QQPetGameActivity` 结束时经 `QQPetActivity.gamePlayedListener` 对大厅页执行——games.ts/community.ts 的 `onGamePlayed` 靠它结算游戏厅内每日奖励（元宝 5/分钟、心情 +2/分钟、成长，日上限 60 分钟）。**改 bridge.ts 契约必须同步本表与实现**；上游只剩这一个实现方，即本模块。

## 刻意不移植清单

| 上游组件 | 不移植理由 |
|----------|-----------|
| PetService 悬浮窗（390 行） | 与 InkForegroundService 前台通知打架；孩子端宠物已有常驻主界面 |
| MainActivity 启动器 + 权限中转 | app-host Launcher 是 PetMainActivity，权限体系已自有 |
| BootReceiver 自启 | setAutoStart 打桩即够 |
| TextActivity PROCESS_TEXT | 剪贴板场景已被 copyText 覆盖 |

伴随打桩无感知的事件：trayClick / outside / clipboard / presence（悬浮窗生态专用，页面有监听但永不触发，无副作用）。

## 存档隔离

- 位置：`filesDir/save.json`（read/write 的 name 参数直接落 filesDir）
- 与 InkLink 宠物状态（EncryptedSharedPreferences + Room `inklink_pet.db`）零交集
- 卸载即失；SAF 导入导出是唯一迁移手段；损坏时上游会另存 `save.json.corrupt-<ts>` 保留现场

## 构建链

```
build job（.github/workflows/android.yml）
1. actions/cache 恢复 .git/lfs（key qqpet-lfs-v1，避免月带宽配额打满）
2. checkout（GIT_LFS_SKIP_SMUDGE）→ git lfs pull → 素材就位守卫（pointer 残留即硬失败）
3. setup-node 22 → npm ci → typecheck → build:android-web（产物 out/android/）
4. setup-java 17 → 三类单测门禁 → assembleRelease
5. aapt 合并 assets.srcDirs 三路；noCompress 媒体直打
6. release job 只下载 inklink-apks，tag v1.2.0-<run_number>
```

- 独立 QQPet APK（net.ankio.qqpet）已不再构建，游戏厅仅以 InkLink-Host 内嵌形态交付
- `third_party/qqpet/android/` 壳源码保留作参照，不参与 CI
- 产物形态：InkLink-Host-release.apk 约 360MB（游戏厅素材全量内置，零服务器硬约束的代价）

## 本地开发流

```bash
# 同步远端 LFS 素材（本地构建 app-host 必需，否则 assets 缺失）
git lfs pull

# 构建游戏厅 web 产物（需 Node 22）
cd third_party/qqpet && npm ci
npm run typecheck && npm run build:android-web

# 常规构建
cd ../.. && ./gradlew :app-host:assembleDebug
```

## 已知风险与待验证

| 项 | 状态 |
|----|------|
| Ruffle（wasm Flash 播放器）在中低端机型帧率 | **未实测**——孩子设备真机验证是唯一未闭环风险 |
| APK 体积约 360MB | 已接受 |
| minSdk 23 兼容 | WindowInsetsControllerCompat + safeArea 的 R 守卫；禁止直接调用 API 30+ |
| 游戏大厅内网络能力（天气/AI 对话/检查更新经 http 桥出网） | 上游既有功能，家庭场景默认放行 |
| save.json 与 InkLink 状态互不感知 | 设计使然；卸载丢档需家长知悉 |

## 相关

- vendor provenance 与素材入库流程：`third_party/qqpet/VENDORED.md`
- 桥契约源码：`third_party/qqpet/src/android/bridge.ts`
- 合并决策复盘：`history-sync/qqpet-integration-2026-10-13.md`
