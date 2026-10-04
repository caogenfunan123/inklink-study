# 批次 B 复盘：HISTORY 协议 + 受控端断点续传传输

日期：2026-10-03　提交：批次 B（feat 链 2/4）

## 交付内容

1. **协议扩展**（common，只增不改）：
   - `HISTORY_REQUEST(53)` / `HISTORY_CHUNK(54)` / `HISTORY_ACK(55)`
   - `HistoryPayloads.kt`：Request(reqId/startTs/endTs/acked?)、Chunk(reqId/seq/total/data)、Ack(reqId/seq)
   - 码位 50-52 已被 PING/PONG/ERROR_RESPONSE 占用，顺延 53-55
2. **受控端 HistoryServer**（app-host/service/HistoryServer.kt）：
   - 确定性分块：区间数据 → 升序 JSONL → 40 行/块 → gzip+Base64
   - 断点续传：REQUEST.acked 带主控端已收序号 → 重新生成分块后只补缺失块
   - reqId 缓存（容量 8，近似 LRU）免重复读文件；收到 ACK 刷新缓存优先级
   - 逐块 30ms 发送间隔防中转限速；空区间返回单个空块作为「完成信号」
   - 挂载：InkForegroundService HISTORY_REQUEST 分发（单线程 executor 串行执行，禁止主线程 sleep）+ HISTORY_ACK 缓存刷新 + startModules 绑定发送通道（定向回传）
3. **单测** `HistoryServerTest` 6 例：全量分块/空区间完成信号/断点续传补缺失/全收零补发/重启后块内容一致（续传前提）/ACK 刷缓存

## 断点续传方案论证

选型「确定性重生成」而非「持久化传输状态」：

- 受控端重启、进程被杀、App 重装（数据在 → 状态丢）后，续传依然成立：重新扫描文件生成的分块序列与上次逐块一致（同一文件同一区间内容不变），主控端把 acked 序号带给新请求，补发逻辑无需感知受控端是否重启过
- 受控端零持久化、零状态迁移；代价是重传时重新读文件+gzip（7 天数据约百毫秒级，可接受）

## 自检发现的 bug（已修复）

1. **空区间死等**：初版空数据返回空分块列表，主控端 total=0 永远等不到完成信号 → 改为返回单个空分片（total=1, data=""），主控端「收满 total」判定自然成立
2. **sender 缺目标地址**：初版 bindSender 只带 chunk，多主控端场景无法定向回传 → 改为 `(target: String?, chunk)` 双参
3. **handleRequest 主线程阻塞风险**：内部有 Thread.sleep（逐块 30ms）+ 文件 IO → 挂载时强制走专用单线程 executor
4. **KDoc 与实现漂移**：头部注释引用不存在的 CACHE_TTL_MS → 改为容量上限表述
5. **编辑器截断**：evictExpired 的 while 循环头在编辑中丢失，回读发现修复

## 遗留风险

- 受控端区间数据文件内容「变化」（新 GPS 落盘跨到已请求区间）时重新生成会改变分块——实际影响：拉取的是历史区间（endTs≈now），请求后新落盘点在区间末端边界可能引入 1 块差异；主控端按 total 判完成，重传请求时 total 以最新生成为准，最终一致。极端场景（拉取 7 天=至今）最后一块可能多次补发，可接受。
- gzip+Base64 的 data 单块峰值约 8KB，即便 64KB 限速也有 8 倍余量。

## 验证

- 6 例单测覆盖续传核心不变量（块内容一致性）；端到端（Ably 真机）待 CI 后真机联调。
