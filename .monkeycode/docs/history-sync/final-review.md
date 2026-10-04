# 历史轨迹补传 · 整体复盘

日期：2026-10-04　范围：批次 A-D（commit 5519095 / bc01847 / b285f66 / 批次 D 工作区）

## 交付总览

四批次一条链：受控端把轨迹落盘 → 协议打通点对点断点续传 → 主控端拉取/合并/进度 → 设备详情页聚合呈现。

| 批次 | 内容 | 提交 |
|------|------|------|
| A | GpsTrackLogger 轨迹落盘（JSONL 按天分桶、30s flush、漂移过滤、30 天清理） | 5519095 |
| B | HISTORY 协议（53 请求 / 54 分块 / 55 ACK）+ HistoryServer 确定性分块续传 | bc01847 |
| C | HistoryClient 拉取客户端（停滞重传/seq 去重/ACK）+ TrackStore 去重合并 + 管理页入口 | b285f66 |
| D | DeviceDetailActivity 单设备聚合页（进度条/取消/围栏/响铃/回放）+ FenceEditActivity 显式设备 | 工作区 |

## 协议设计（零服务器铁律下的离线补传）

- 点对点：主控端 REQUEST(53, reqId, startTs, endTs, acked) → 受控端确定性生成分块（40 行/块，gzip+Base64 约 3-8KB，8 倍消息余量）→ CHUNK(54, seq, total, data) 逐块 30ms 防限速 → 主控端解码合并 TrackStore → ACK(55)
- 断点续传 = 确定性重生成：受控端零持久化传输状态；主控端停滞 15s 重发 REQUEST 带已收 seq，受控端重新生成同分块只补缺失（受控端重启不影响）
- 完成判定：收满 total；空区间用单空块（total=1, data=""）作完成信号，防空数据死等
- 码位纪律：53/54/55 插在 49（学习系统）与 99（心跳）之间，只增不改

## 整体一致性审查发现的问题（已全部修复）

审查方式：四批次交叉审查（符号/线程/协议/资源/测试五维），修复后在最终推送前完成回归。

1. **[blocker] readRange 死循环**（GpsTrackLogger）：`day += DAY_MILLIS` 在 endTs=Long.MAX_VALUE 场景溢出回绕成负值，`day <= endTs` 恒成立 → 死循环。且无迭代上限，极端区间逐天文件探测失控。修复：endTs 钳制 MAX-DAY_MILLIS + MAX_SCAN_DAYS(36600) 双保险
2. **[blocker] HistoryServerTest 断言错**：`retry[0].total` 断言 1，实际 3（total 恒为本次请求总分块数）。修复：改 3
3. **[blocker] HistoryClientTest 断言与意图不符**：「重复块」用例 seq 1 携带了新点，实际新增 2 而断言 1。修复：seq 1 改为携带与 seq 0 相同的点，真正覆盖跨块内容去重
4. **[major] HistoryServer 缓存跨线程**：onAck 走传输回调线程，handleRequest/chunksFor 走 historyExecutor，裸 LinkedHashMap 并发读写可能 CME。修复：缓存所有访问路径 synchronized(cache)
5. **[minor] 编辑残留**：applyGeofence 签名与首语句粘连一行 → 修复换行
6. **[minor] 未使用 import**：HistoryClient 四个协议 import 清理
7. **[minor] 冗余判断**：`chunks.size > 1` 外层 if 删除（内层 seq 判定已隐含）
8. **[minor] seq 越界无防护**：HistoryClient.onChunk 增加 total<=0 / seq<0 / seq>=total 忽略（防缓存回收后 total 缩小导致提前误判完成）；HistoryServer 侧 acked 越界序号经核实天然无害（forEachIndexed seq 本就在合法区间），补注释说明
9. **[minor] 测试缺口**：新增 round-trip 契约测试——GpsTrackLogger 落盘 JSONL 键名（t/la/lo/sp/ac）可被 TrackStore.TrackPoint 直接解析合并，钉死「零转换」契约；新增 seq/total 非法块忽略用例
10. **[minor] 线程泄漏**：GpsTrackLogger/HistoryClient 增加 close()（flush+shutdown），InkForegroundService.onDestroy 停 historyExecutor + logger.close()，三个测试类 teardown 关闭
11. **[minor] API 脆弱边界**：HistoryClient 增加 `require(stallTimeoutMs >= 2)`（调度周期 = timeout/2，为 0 时 ScheduledExecutorService 拒绝构造）

## 遗留风险（文档化，暂不处理）

- **传输层静默丢弃**（TransportManager）：超 64KB 消息 return 静默丢弃仅告警。若未来调大 CHUNK_LINES 或单行体积异常致分块超限，主控端停滞重传只会重发同一超限块，6 次后判失败，无法自愈。当前 40 行/块约 3-8KB 余量充足。后续如调大块尺寸，应在传输层把「已丢弃」反馈给上层
- **停滞重发受单线程 io 排队影响**：onChunk 内同步等待 TrackStore 写入（latch），gzip 解码+写盘耗时会推迟 checkStall，最坏情况拉取变慢，无正确性影响
- 单并发拉取：同时只能拉一台设备历史（产品上合理：家长逐台拉取）
- 端到端（真机双端联调）未验证，CI 门禁覆盖编译与 30+ 例单测

## 测试覆盖

- GpsTrackLoggerTest 5 例 + HistoryServerTest 5 例（受控端）+ TrackStoreMergeTest 6 例 + HistoryClientTest 7 例（主控端），含 round-trip 契约、空块完成、停滞重传带 acked、取消、seq 去重、内容去重

## 推送后 CI 发现的问题（已修复，commit 9e00beb/6cac652）

复盘与本地审查均未覆盖编译期与渲染期问题，两条均由 CI/自查在推送后抓出：

1. **[blocker] ExecutorService 类型错误**（GpsTrackLogger:48 / HistoryClient:70）：`Executors.newSingleThreadExecutor()` 返回 `ExecutorService`，没有 `scheduleWithFixedDelay`（属 `ScheduledExecutorService`）→ 两个模块 compileDebugKotlin 全挂。这是本代码首次上 CI（A-D 一直本地未推），类型问题从批次 A 起潜伏。修复：两处改 `newSingleThreadScheduledExecutor()`（execute/submit/shutdown 接口不变）
2. **[major] 空按钮**（activity_device_detail.xml）：btn_fence/btn_track/btn_fetch_history 布局未设 android:text、代码也未 setText → 渲染成无文字按钮。修复：XML 绑定 edit_fence/fetch_history 既有文案 + 新增 track_replay

教训：跨批次符号级审查代替不了编译器；UI 资源类问题（缺失文案/id）应作为独立检查项，不依赖代码逻辑审查顺带覆盖。

## 验证状态

- CI run 37167402332（6cac652）成功：编译 + 全量单测通过，Release v1.2.0-44
- 端到端（真机双端联调）待验证

## 下一步候选（未承诺）

- 真机双端联调（100+ 点、弱网中断、受控端重启续传三场景）
- 地图页回放支持拉取到的历史区间（当前回放仅用内存实时轨迹）
- 传输层超限消息反馈机制
