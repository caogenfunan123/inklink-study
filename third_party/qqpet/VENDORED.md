# Vendored: QQPet (QQ 宠物单机复刻版)

- 上游仓库: https://github.com/AnkioTomas/qqpet
- 上游 commit: db7cf74f8dd0770ead920177338ba05bb6c24ec6
- 引入日期: 2026-10-13
- 引入方式: 整仓快照 vendor（无 submodule、无历史），素材走本仓库 LFS
- 用途: **仅限仓库所有者个人学习与本地游玩，不分发、不商用**

## 许可与版权边界

- 代码（src/ android/ harmony/ scripts/ tools/ 等）: GPL-3.0，见本目录 LICENSE。
  本仓库已转私有、产物仅私有发布，未触发 GPL 分发义务；若未来公开本仓库或分发含此代码的产物，须整体按 GPL-3.0 开源。
- resources/pet 下腾讯 QQ 宠物美术/SWF 与旅行照片素材: 版权归腾讯/原作者所有，
  GPL 不覆盖该目录，随附上游版权声明，仅供学习，严禁商用与再分发。

## 集成说明

- 完全独立的第三方 App（包名 net.ankio.qqpet），与 InkLink 双端（com.inklink.*）互不影响。
- CI: `.github/workflows/android.yml` 单 job 追加 QQPet 构建步骤，
  `npm ci → typecheck → vite build:android-web → android/gradlew assembleRelease`，
  复用仓库内 keystore/inklink-release.keystore 与 INKLINK_* secrets 签名，产物 dist/QQPet-release.apk。
- LFS: resources/pet 素材（约 329MB / 8058 文件）由本仓库 LFS 承载，
  CI 用 actions/cache 缓存 .git/lfs（key: qqpet-lfs-v1，素材更新时手工升版本号）。
- 升级上游: 在上游 clone 中 `git lfs pull` 后，整体覆盖本目录（保留本文件），更新上面的 commit 号。
