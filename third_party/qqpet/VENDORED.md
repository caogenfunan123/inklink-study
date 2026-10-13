# Vendored: QQPet (QQ 宠物单机复刻版)

- 上游仓库: https://github.com/AnkioTomas/qqpet
- 上游 commit: db7cf74f8dd0770ead920177338ba05bb6c24ec6
- 引入日期: 2026-10-13
- 引入方式: 整仓快照 vendor（无 submodule、无历史），素材走本仓库 LFS
- 仓库可见性: **public**（公开仓库 Actions 存储不计 Free 500MB 配额）

## 许可与版权边界

- 代码（src/ android/ harmony/ scripts/ tools/ 等）: GPL-3.0，见本目录 LICENSE。
  本仓库公开分发已随附 LICENSE，满足 GPL 合规；InkLink 自身 Kotlin 代码为独立模块独立 APK，
  仅同仓共存，不受 GPL 传染。
- resources/pet 下腾讯 QQ 宠物美术/SWF 与旅行照片素材: 版权归腾讯/原作者所有，
  GPL 不覆盖该目录。公开仓库 = 公开再分发，该风险由仓库所有者自行承担，随附上游版权声明。

## 集成说明

- 完全独立的第三方 App（包名 net.ankio.qqpet），与 InkLink 双端（com.inklink.*）互不影响。
- CI（.github/workflows/android.yml）三 job:
  - `build`: InkLink 双端单测门禁 + assembleRelease，产出 artifact `inklink-apks`
  - `qqpet`: LFS 缓存 restore → GIT_LFS_SKIP_SMUDGE checkout → git lfs pull → 守卫（目录缺失或指针残留则跳过）
    → npm ci → typecheck → vite android-web → android/gradlew assembleRelease
    → 复用 keystore/inklink-release.keystore + INKLINK_* secrets 签名 → artifact `qqpet-apks`
  - `release`: 汇聚双 artifact 发版；**仅 `v*` tag push 或手动 workflow_dispatch 触发**，普通 push 不建 Release
- LFS: resources/pet 素材（约 329MB / 8058 文件）由本仓库 LFS 承载；CI 用 actions/cache
  缓存 .git/lfs（key qqpet-lfs-v1，素材更新时手工升版本号），避免每次拉满 LFS 月带宽配额

## LFS 素材入库流程（沙盒出网限速 ~12KB/s，必须由所有者本地机器执行）

```bash
# 1. 克隆主仓（公开，代码仅 ~7MB，clone 后 resources/pet 为指针文本）
git clone https://github.com/caogenfunan123/inklink-study.git && cd inklink-study

# 2. 克隆 qqpet 上游并拉取 LFS 素材（需本机 git lfs install）
git clone --depth 1 https://github.com/AnkioTomas/qqpet.git /tmp/qqpet-src
cd /tmp/qqpet-src && git lfs pull && cd -

# 3. 素材搬进 vendor 目录（代码已在仓库，只补素材）
mkdir -p third_party/qqpet/resources
cp -a /tmp/qqpet-src/resources/. third_party/qqpet/resources/

# 4. 提交推送（LFS 对象随 git push 自动上传，家用宽带约几分钟）
git add third_party/qqpet/resources
git commit -m "chore(vendor): QQPet LFS 素材入库（329MB）"
git push origin master
```

推送完成后 CI 的 qqpet job 自动恢复构建，tag 或手动 dispatch 时产出三 APK Release。

## 本地手动构建 QQPet（不经 CI）

```bash
cd third_party/qqpet
npm ci && npm run typecheck && npm run build:android-web
cd android
ANDROID_KEYSTORE=/path/to/keystore INKLINK_KEYSTORE_PASSWORD=... INKLINK_KEY_ALIAS=inklink INKLINK_KEY_PASSWORD=... \
  ./gradlew assembleRelease
```

## 升级上游

在上游 clone 中 `git lfs pull` 后整体覆盖本目录（保留本文件），更新上面的 commit 号。
