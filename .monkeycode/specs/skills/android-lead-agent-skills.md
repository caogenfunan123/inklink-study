---
name: android-lead-agent-skills
description: InkLink-Pet 架构与业务安全审查 Skill。强制绑定 design.md 核心规范。
rules:
  - "唯一真相源：InkForegroundService 为全业务唯一真相源；UI 仅订阅展示，严禁 UI 视图反向修改业务数据。"
  - "生命周期隔离：UI 划掉销毁绝不影响后台通信、GPS、围栏与告警服务的常驻运行。"
  - "音频流隔离：宠物娱乐音效/TTS 必须绑定 STREAM_MUSIC；强控告警必须绑定 STREAM_ALARM 并优先强行打断 TTS。"
  - "零信任防御：所有网络传入参数必须在受控端本地执行范围校验；高危指令必须校验 HMAC-SHA256 签名与 +-60s 防重放时间窗。"
  - "时间增量模型：属性衰减仅在 onResume 与网络指令到达时单次计算，严禁常驻后台循环定时器。"
---
