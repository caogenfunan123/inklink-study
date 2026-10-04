package com.inklink.host.service

import android.util.Base64
import com.google.gson.Gson
import com.inklink.common.protocol.payload.HistoryChunkPayload
import com.inklink.common.protocol.payload.HistoryRequestPayload
import com.inklink.host.data.GpsTrackLogger
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * 受控端历史轨迹补传服务端。
 *
 * 断点续传设计（无需持久化传输状态）：
 * - 分块确定性：对同一 [startTs,endTs] 区间，文件内容不变则生成的分块序列
 *   （每块 [CHUNK_LINES] 行、按时间升序）逐字节一致，seq 与 total 稳定；
 * - 重传协议：主控端在重发的 REQUEST.acked 里带已收块序号，本端重新生成
 *   分块后只补发缺失块；受控端进程重启也不影响（重新生成即续传）；
 * - 短时缓存：同一 reqId 的分块结果按容量上限缓存（[CACHE_MAX_ENTRIES]，近似 LRU），
 *   重复请求免去重复读文件；超出上限回收最旧请求（重新生成即恢复，分块确定性保证 seq 不变）。
 *
 * 发送节流：逐块间隔 [SEND_INTERVAL_MS]，避免背靠背数百条消息触发 Ably 限速。
 */
class HistoryServer(private val trackLogger: GpsTrackLogger) {

    private val gson = Gson()

    /** reqId → 分块数据缓存（seq → 已编码分片）。 */
    private val cache = LinkedHashMap<String, List<String>>()

    /** 发送回调：由 Service 注入（负责构造 InkMessage 并 sendMessage，第一参为回传目标 deviceId）。 */
    private var sender: ((String?, HistoryChunkPayload) -> Unit)? = null

    fun bindSender(sender: (String?, HistoryChunkPayload) -> Unit) {
        this.sender = sender
    }

    /**
     * 处理拉取请求（调用方保证在后台线程，内部有逐块发送间隔）：
     * flush 轨迹缓冲 → 生成/取缓存分块 → 补发缺失块。
     * 返回本次实际发送的块数（供日志）。
     *
     * acked 中越界序号（>= chunks.size）天然无害：forEachIndexed 的 seq 本就只在合法区间。
     */
    fun handleRequest(payload: HistoryRequestPayload, target: String?): Int {
        val sender = sender ?: return 0
        if (payload.endTs < payload.startTs) return 0

        val chunks = chunksFor(payload.reqId, payload.startTs, payload.endTs)
        val acked = payload.acked?.toSet() ?: emptySet()
        var sent = 0
        chunks.forEachIndexed { seq, data ->
            if (seq in acked) return@forEachIndexed
            sender(target, HistoryChunkPayload(payload.reqId, seq, chunks.size, data))
            sent++
            // 逐块间隔，防背靠背触发中转限速；末块不等待
            if (seq < chunks.size - 1) Thread.sleep(SEND_INTERVAL_MS)
        }
        return sent
    }

    /** 收到主控端 ACK：仅刷新缓存优先级（传输进行中防止被回收）。 */
    fun onAck(reqId: String) {
        // ACK 走传输回调线程，与 handleRequest 所在的 historyExecutor 并发访问缓存 → 统一加锁
        synchronized(cache) {
            cache[reqId]?.let { chunks ->
                cache.remove(reqId)
                cache[reqId] = chunks
            }
            evictExpiredLocked()
        }
    }

    /**
     * 生成（或取缓存）分块：JSONL 行按 [CHUNK_LINES] 分组，gzip+Base64。
     * 区间无数据时返回单个空分片（total=1, data=""），让主控端以「收满 total」
     * 判定完成——否则空结果会一直等到超时。
     */
    private fun chunksFor(reqId: String, startTs: Long, endTs: Long): List<String> {
        evictExpired()
        synchronized(cache) { cache[reqId]?.let { return it } }

        trackLogger.flush()
        val lines = trackLogger.readRange(startTs, endTs).map { gson.toJson(it) }
        val chunks = if (lines.isEmpty()) {
            listOf("")
        } else {
            lines.chunked(CHUNK_LINES).map { slice ->
                Base64.encodeToString(gzip(slice.joinToString("\n")), Base64.NO_WRAP)
            }
        }
        synchronized(cache) { cache[reqId] = chunks }
        return chunks
    }

    /** 容量上限回收：仅保留最近 [CACHE_MAX_ENTRIES] 个请求（近似 LRU）。 */
    private fun evictExpired() {
        synchronized(cache) { evictExpiredLocked() }
    }

    /** 调用方须持有 [cache] 锁。 */
    private fun evictExpiredLocked() {
        while (cache.size > CACHE_MAX_ENTRIES) {
            val eldest = cache.keys.firstOrNull() ?: break
            cache.remove(eldest)
        }
    }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    companion object {
        /** 每块行数：40 行 gzip+Base64 约 3-8KB，远低于 Ably 64KB 消息上限。 */
        const val CHUNK_LINES = 40

        /** 逐块发送间隔（毫秒）。 */
        const val SEND_INTERVAL_MS = 30L

        /** 分块缓存容量上限（LRU 式回收最旧请求）。 */
        const val CACHE_MAX_ENTRIES = 8
    }
}
