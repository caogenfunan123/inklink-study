# app-controller 家长端（主控端）

家长手机上的遥控台：设备管理、地图围栏、宠物远程关怀、强控指令、告警接收。无 Service/Receiver/Room，全部逻辑寄生于 Application 进程。

## 结构

```
app-controller/src/main/java/com/inklink/controller/
├── InkControllerApplication.kt  # 473行 全局单例持有者 + 消息路由
│   # route() 分发：GPS_REPORT/DEVICE_STATUS/CMD_ACK/PING(PONG应答)/HEARTBEAT/
│   # ALERT_*/PET_ALERT_EVENT/LEARN_PROGRESS/AUDIO_START_STOP/CHAT_*
│   # markSeen 刷新设备活跃时间；连接管理 connectLocal/switchRelay/switchAbly
├── state/ControllerState.kt     # 多设备聚合：gpsByDevice/statusByDevice/
│                                 # trajectoryByDevice(500点)/learnSummaryByDevice(内存)/
│                                 # selectedDeviceId/isOnline(60s阈值)/dispatchAck
├── care/CareMonitor.kt          # 60s评估：离线超30min提醒/连续在线2h休息提醒（冷却30min）
├── data/
│   ├── DeviceEntity.kt          # deviceId + nickname
│   └── DeviceRepository.kt      # SharedPreferences(inklink_devices) + JSON
├── service/RouteApi.kt          # 腾讯驾车路线：SK MD5签名 + polyline 1e-5偏移解压
├── audio/AudioSettings.kt       # TTS播报全局开关（关闭时源头不注入ttsText）
└── ui/
    ├── ControllerActivity.kt    # 452行 Launcher Dashboard：设备卡流(三态徽章/电量/坐标/
    │                            # 学习简报)/强控指令/投屏/扫码连接/传输模式设置
    ├── MapActivity.kt           # 腾讯地图：受控红Marker+蓝轨迹+主控绿Marker+驾车路线
    ├── FenceEditActivity.kt     # 地图选圆心+SeekBar半径，GCJ-02→WGS-84反变换后下发
    ├── DeviceManageActivity.kt  # 多设备绑定/点选目标/改备注/删除
    ├── PetDetailActivity.kt     # 567行 宠物关怀台：互动31(长按5连投)/事件音23/系统音22/
    │                            # 语音任务45+46回执→10金币闭环/三类赠礼44/逗弄43-TEASE/
    │                            # 猜拳33-34/饥饿告警一键补喂/listener链式包装+onDestroy恢复
    └── ChatActivity.kt          # 文字/图片/AMR语音气泡
```

## 关键文件

| 文件 | 目的 |
|------|------|
| `InkControllerApplication.kt` | 一切入站消息的归属地；新消息类型在此加 route 分支 |
| `state/ControllerState.kt` | 页面间共享状态的唯一载体；变更统一 post 主线程 |
| `ui/PetDetailActivity.kt` | 宠物远程交互全量入口；临时订阅 TransportListener 的链式包装模式在此 |

## 依赖

**依赖**：project(:common)、appcompat、constraintlayout、material、腾讯地图 vector-sdk 6.13.0 + foundation、zxing-android-embedded
**被依赖**：无

## 与受控端的结构性差异

1. 无前台服务：语音采集/定位挂在 Application 进程回调，进程被杀即全停（接受现状，家长端前台使用为主）
2. 无 Room：设备列表 SharedPreferences JSON；学习简报仅内存（M3 需引入持久化，见 DEVELOPER_GUIDE 已知坑）
3. 语音对讲：startCall 发 AUDIO_START(9)，收到受控端确认后本端才开始采集

## M3 开发入口（待办）

- 作业布置：新增 HomeworkActivity，发 HOMEWORK_ASSIGN(48)；HomeworkAssignPayload 已在 common 就绪
- 学习报告：新增 LearnReportActivity，持久化 47 码数据（方案：Room 或 JSON 文件，注意与 IMPLEMENTATION_PLAN 第 9 节「无后端清 App 丢历史」口径对齐）
- Dashboard 设备卡加「学习简报」两行 + 「布置作业」按钮

## 规范

- 主控端发指令统一走 InkControllerApplication 的 sendXxx API（内置 targetDeviceId、TTS 开关过滤、ACK Toast 回显）
- 地图相关全部 GCJ-02：展示前 WGS-84→GCJ-02，下发围栏前反变换
- 单测 2 类：ControllerStateTest（聚合/在线判定）/ DeviceRepositoryTest（CRUD+去重）；均不在 CI 门禁内
