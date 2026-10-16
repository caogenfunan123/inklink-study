# QQPet 游戏厅合并与撤回记录（2026-10-13）

> **状态：已撤回（同日）**。本文是事件记录，不是现行设计。现行状态：app-host 无游戏厅、无第三方 vendor、
> 无 LFS；CI 为 build/release 两 job。撤回原因：功能未达预期，且 360MB APK 不值得。
> 完整过程仍在 git 历史（合并 3e6de7b7 / 素材 966a6399），可捡回。

## 发生了什么

1. 下线原生单机小游戏（猜拳/翻牌/转盘）与街机渲染模式（ff5f9ae）
2. vendor AnkioTomas/qqpet 347MB 素材经 CI 入库（966a6399）
3. 游戏厅合并进 app-host（qqpet/ 三文件 + CI 改两 job，3e6de7b7），Release v1.2.0-70 产出 360MB InkLink-Host
4. 用户实测「还是以前的东西」——未达预期；当日决定整体撤回

## 撤回执行（同日）

- revert 3e6de7b7 + 8c949949 + 两个文档提交，回到 ff5f9ae 状态
- `git rm -r third_party/qqpet`（代码 5.8MB + LFS 素材 347MB 连根删）、删 assets-import.yml 工作流
- .gitignore 移除四条 vendor 豁免；android.yml 去 qqpet job，恢复无 LFS/无 node 的两 job
- 「🎮游戏」按钮回到 GONE；versionCode 保持 2/0.2.0（防装过 360MB 包的设备降级拦截）
- 文档全部回滚到合并前口径

## 教训（下次合并第三方前先读）

1. **先真机、后入库**：上游是「悬浮窗桌面宠」形态，UI 为 overlay 场景设计，直接搬进全屏 Activity
   未必成立。体积大的第三方素材，应在本地小样验证体验达标后再谈 CI 入库，避免 347MB 进去了再连根拔
2. **移植壳代码要查事件消费方**：gamePlayed 断链那次（奖励静默不结算）说明 emit/回调/监听三条线
   必须逐条对消费方，Kotlin 侧「看起来多余」的代码可能在 web 侧是奖励链路
3. **公开仓库 + LFS 素材是重决策**：腾讯版权素材公开再分发、GPL 代码随仓，一旦推送即公开留存，
   撤回也只能删引用（LFS 对象 lingered 到 GitHub GC）
4. **v1.2.0-70 Release（含 360MB APK）仍挂在 GitHub**：tag 与 release 资产不随分支撤回消失，
   如需下架该 Release 要单独操作

## 恢复入口

```bash
# 代码 + 素材指针（LFS 对象仍在 GitHub 存储，未 GC 前有效）
git checkout 966a6399 -- third_party/qqpet
git lfs pull
```

上游仓库：https://github.com/AnkioTomas/qqpet （commit db7cf74）
