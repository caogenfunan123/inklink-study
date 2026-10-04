# 批次 C 复盘：主控端拉取/进度/去重合并

日期：2026-10-03　提交：批次 C（feat 链 3/4）

## 交付内容

1. **TrackStore 扩展**：`appendPoints(deviceId, points, onDone)` 批量去重合并——按点时间戳分日期文件，与文件内既有 ts + 批内 ts 双重去重，漂移点丢弃，回调返回实际新增数；新增 `totalPoints` 统计辅助（单测用）
2. **ControllerState**：`HistoryProgress(deviceId, reqId, received, total, inserted, done, failed, failedReason)` + `setHistoryProgress`，走既有 Listener 通知模式
3. **HistoryClient**（app-controller/data/HistoryClient.kt）：
   - 单并发任务管理（重复 start 先取消上一个）；reqId 由客户端生成
   - 收 CHUNK：reqId/来源校验 → seq 去重 → 解码（gzip+Base64 JSONL）→ TrackStore 合并 → 回 ACK → 进度上报；收满 total 完成
   - 停滞检测（默认 15s 无进展重发 REQUEST 带 acked 序号，重试 6 次判失败）；超时参数化供单测
   - 专用单线程 io；cancel 支持并上报失败态
4. **InkControllerApplication**：historyClient lazy 挂载 + bindSenders（REQUEST/ACK 定向发送）+ route 分发 HISTORY_CHUNK + 公开 `requestHistory(deviceId, days)/cancelHistory()`
5. **UI 入口**（临时）：DeviceManageActivity 长按菜单加「拉取历史轨迹」（1/3/7 天），listener 观察 historyProgress 完成/失败 Toast（reqId 去重防重复弹）
6. **单测**：TrackStoreMergeTest 5 例（空批/跨天分文件/批内去重/实时数据互补去重/漂移丢弃）+ HistoryClientTest 6 例（全量完成/重复块/reqId 来源校验/空块完成/停滞重发带 acked/取消）

## 与受控端续传协议的配合

- 主控端停滞重发 `REQUEST(reqId 同, acked=[...])` → 受控端重新生成分块（确定性）→ 只补缺失块 → 主控端收满 total 完成
- 受控端重启场景：块内容不变 → seq 对齐 → 补发正确；受控端数据变化的边界场景见批次 B 复盘遗留风险
- HistoryClient 收满 `received.size >= total` 即完成：乱序到达、重复到达均收敛

## 自检发现的 bug（已修复）

1. **mergeChunk 死等**：CountDownLatch 只有 await 没 countDown（onDone 里漏调）→ 每 chunk 白等 10s 超时。修复：onDone 内 countDown（latch 的 happens-before 同时解决跨线程 inserted 可见性）
2. **reqId 断链**：初版 requestSender 签名无 reqId，Application 侧凭空造了 `currentHistoryReqId()` → 改为 HistoryClient 生成 reqId 并随 sender 传出（REQUEST 与 ACK 均携带）
3. **单测时区脆弱**：硬编码 "20231114" 日期断言在 CI（UTC）与本机（东八）结果不同 → 改用 listDays/totalPoints 聚合断言
4. **测试杂音**：`assertNull(null)` 废断言清理

## 遗留风险

- 停滞重发的最小间隔受单线程 io 排队影响：onChunk 处理（gzip 解码+文件写入同步等待）耗时过长会推迟 checkStall，最坏情况拉取变慢，无正确性影响
- 单并发限制：同时只能拉一台设备的历史（产品上合理，家长逐台拉取）

## 验证

- 11 例单测覆盖合并去重与客户端状态机；端到端待真机联调。
