# 历史轨迹补传 · 子系统架构

日期：2026-10-04　状态：四批次交付完成（A 落盘 / B 协议 / C 拉取 / D 呈现）

## 组件与代码位置

```
受控端（孩子机 app-host）
├── data/GpsTrackLogger.kt        轨迹落盘：filesDir/gps_log/<yyyyMMdd>.jsonl
└── service/HistoryServer.kt      分块续传服务端（确定性分块+reqId 缓存+逐块节流）

公共（common）
├── protocol/MessageType.kt       HISTORY_REQUEST(53)/CHUNK(54)/ACK(55)
└── protocol/payload/HistoryPayloads.kt  HistoryRequestPayload/HistoryChunkPayload/HistoryAckPayload

主控端（家长机 app-controller）
├── data/HistoryClient.kt         拉取客户端（停滞重传/seq 去重/ACK/进度上报）
├── data/TrackStore.kt            轨迹存储（实时 append + 补传 appendPoints 去重合并）
├── state/ControllerState.kt      HistoryProgress + Listener 通知
├── ui/DeviceDetailActivity.kt    单设备聚合页（含拉取进度条/取消）
└── ui/FenceEditActivity.kt       支持 EXTRA_DEVICE_ID 显式设备（详情页穿透下发）

接线
├── app-host/.../InkForegroundService.kt   trackLogger/historyServer 挂载、historyExecutor、HISTORY 分发
└── app-controller/.../InkControllerApplication.kt  historyClient 挂载、route HISTORY_CHUNK、requestHistory/cancelHistory
```

## 数据流

```
GPS 回调 → reportThrottler.shouldReportLocation（8m/5s 节流点）
         ├→ broadcastGpsReport（实时上报主控端）
         └→ trackLogger.append（落盘，与实时同源同节流）

主控端拉取：
HistoryClient.start(deviceId, days)
  → REQUEST(reqId, startTs, endTs, acked)
  → HistoryServer.handleRequest
       → trackLogger.flush() → readRange(start, end) → 40 行/块 gzip+Base64
       → 只发 seq ∉ acked 的块（间隔 30ms）
  → CHUNK(seq, total, data) ×N
  → HistoryClient.onChunk：reqId/来源校验 → seq 去重 → 解码 → TrackStore.appendPoints（ts 去重）→ ACK(seq) → 进度
  → 收满 total 完成；空区间单空块（total=1, data=""）即完成
停滞 15s 无进展 → 重发 REQUEST（reqId 不变，acked 带已收 seq），6 次判失败
```

## 关键设计

| 决策 | 理由 |
|------|------|
| 确定性分块（40 行/块，sortedBy t 稳定） | 受控端零持久化传输状态；重启/进程被杀后续传依然成立 |
| reqId 缓存（容量 8，近似 LRU，synchronized 保护跨线程） | 重复请求免重复读文件；缓存回收后重新生成 seq 不变（确定性） |
| 空块完成信号 | 空区间若返回 total=0，主控端「收满 total」判完成永远不成立 → 死等超时 |
| 落盘键名与 TrackStore.TrackPoint 逐字对齐（t/la/lo/sp/ac） | 合并零转换；有 round-trip 单测钉死契约 |
| 单并发拉取 | 家长逐台拉取，产品上合理；避免多任务互相争抢 io 与 TrackStore 写锁 |
| 主控端主导停滞重发 | 受控端无状态，「等不到就重问」比受控端记账简单可靠 |
| 进度经 ControllerState.Listener（mainHandler.post） | UI 线程安全，与既有设备状态同一条通知链路 |

## 线程模型

- 受控端：GPS 回调 → append（内存 buffer，synchronized）；GpsTrackLogger 单线程 io 负责 flush/写盘；historyExecutor 串行化 handleRequest（内部有 30ms 逐块 sleep，禁止主线程）；HISTORY_ACK 在传输回调线程直接进 onAck（缓存访问已加锁）
- 主控端：HistoryClient 单线程 io 串行化 start/onChunk/checkStall；onChunk 内同步等待 TrackStore 写入（CountDownLatch 保 inserted 计数准确）；进度回调切主线程

## UI 入口

- DeviceManageActivity 长按 → 「拉取历史轨迹」（1/3/7 天，快速路径，Toast 回报结果）
- DeviceManageActivity 长按 → 「查看详情」→ DeviceDetailActivity（进度条 + 取消 + 围栏/响铃/回放聚合）
- 详情页「编辑围栏」→ FenceEditActivity 带 EXTRA_DEVICE_ID（不依赖全局选中态）

## 规模约束

- 消息上限 64KB：40 行/块 gzip+Base64 约 3-8KB（8 倍余量）。调大 CHUNK_LINES 前先评估传输层超限静默丢弃风险（见 final-review.md 遗留风险）
- 受控端保留 30 天轨迹（启动时清理）；主控端 days 参数钳制 1-30 天与保留期对齐
