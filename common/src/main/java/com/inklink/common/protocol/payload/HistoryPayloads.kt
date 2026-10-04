package com.inklink.common.protocol.payload

/**
 * 历史轨迹补传载荷（HISTORY_REQUEST/CHUNK/ACK 53/54/55）。
 *
 * 传输模型：
 * 1. 主控端发 REQUEST(reqId, startTs, endTs)，重传时带 [HistoryRequestPayload.acked] 已收序号；
 * 2. 受控端从本地轨迹文件确定性生成全量分块（同输入 → 同块序 → 同 total），只发未 acked 的块；
 * 3. CHUNK(seq, total, data)，data 为 gzip + Base64 的 JSONL 分片（每片 CHUNK_LINES 行）；
 * 4. 主控端每收一块回 ACK(seq)，收满 total 即完成；超时无进展则重发 REQUEST 补缺失块。
 */
data class HistoryRequestPayload(
    val reqId: String,
    val startTs: Long,
    val endTs: Long,
    /** 断点续传：主控端已完整收到的 chunk 序号列表（缺省/null 视为从零开始）。 */
    val acked: List<Int>? = null
)

data class HistoryChunkPayload(
    val reqId: String,
    /** 分块序号，0 起。 */
    val seq: Int,
    /** 本次请求的总分块数（确定性生成，重传间不变）。 */
    val total: Int,
    /** gzip + Base64(NO_WRAP) 的 JSONL 分片。 */
    val data: String
)

data class HistoryAckPayload(
    val reqId: String,
    val seq: Int
)
