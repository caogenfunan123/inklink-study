# 批次 A 复盘：受控端 GpsTrackLogger 轨迹落盘

日期：2026-10-03　提交：批次 A（feat 链 1/4）

## 交付内容

`app-host/src/main/java/com/inklink/host/data/GpsTrackLogger.kt`（新建）

- 目录 `filesDir/gps_log/<yyyyMMdd>.jsonl`，受控端只记录自身轨迹
- 点格式 `{t, la, lo, sp, ac}` 与主控端 `TrackStore.TrackPoint` **完全一致**——批次 C 合并零转换
- 写入挂现有上报节流点（8m/5s）：`InkForegroundService.onGpsLocation` 里 `broadcastGpsReport` 同点落盘
- 内存按天分桶 buffer + 30s 定时 flush + 服务 onDestroy 强制 flush
- accuracy > 50m 漂移点丢弃（与 TrackStore 一致）；30 天滚动清理
- 单测 `GpsTrackLoggerTest` 5 例：格式对齐/漂移过滤/范围读+升序/跨天归属/未 flush 不可见约定

## 设计决策与理由

| 决策 | 理由 |
|---|---|
| 记录密度与上报共用节流点 | 主控端在线时收到的点 ⊆ 受控端落盘点，两边数据天然对齐，合并去重逻辑简单；静止不写省磁盘省电 |
| TrackPoint 键名复用主控端缩写格式 | 批次 C 收到 chunk 直接 `fromJson(TrackPoint)` 写入，无字段映射 |
| 按天分桶 buffer 而非单 buffer | 初版单 buffer + flush 时取当前日期存在跨天归属 bug（23:59 写入 00:00 flush 记到错天），改为 append 时按 `report.time` 分桶 |
| readRange 只读文件 | 拉取前必须先 `flush()`，把约定写进 KDoc 与单测（第 5 例） |

## 自检发现的 bug（已修复）

1. **跨天归属错误**（初版）：flush 用 `System.currentTimeMillis()` 决定目标文件，跨天窗口把昨晚的点写进今天的文件 → readRange 按天扫描会漏。修复：buffer 按天分桶。
2. **flush 期间数据丢失面**：`flushInternal` 先清 buffer 再写盘，`appendText` 失败（磁盘满/IO 异常）丢最多 30s 数据。评估：可接受，不做失败回塞（过度设计），已记录。

## 遗留风险

- flush 定时器挂在单线程 executor 的 `scheduleWithFixedDelay`，若 flush 阻塞（IO 慢）不影响 GPS 回调（append 只进内存），最坏丢 buffer 内数据——符合设计。
- 服务被系统杀死（非 onDestroy 路径）丢 buffer 内 ≤30s 数据。可接受。

## 验证

- 本地无工具链，单测验证走 CI（批次全量推送后统一确认）。
