package com.inklink.controller.data

import android.util.Base64
import com.google.gson.Gson
import com.inklink.common.protocol.payload.HistoryChunkPayload
import com.inklink.common.utils.MonoClock
import com.inklink.controller.state.ControllerState
import com.inklink.controller.state.HistoryProgress
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 主控端历史轨迹拉取客户端（点对点断点续传）。
 *
 * 传输模型（与受控端 HistoryServer 对应）：
 * - [start] 发 REQUEST(reqId, startTs, endTs)；
 * - 每收 CHUNK(seq,total,data)：解码合并入 TrackStore（时间戳去重）→ 回 ACK(seq)；
 * - [STALL_TIMEOUT_MS] 无进展（未收满且无新块）→ 重发 REQUEST 带 acked 已收序号（受控端确定性
 *   重生成分块，只补缺失块）；连续 [MAX_RETRIES] 次无进展判失败；
 * - 收满 total（或收到空块 data=""）即完成。
 *
 * 单并发：同一时刻至多一个拉取任务，重复 start 先取消上一个。
 */
class HistoryClient(
    private val trackStore: TrackStore,
    private val controllerState: ControllerState,
    private val stallTimeoutMs: Long = STALL_TIMEOUT_MS,
    /** 停滞判定时钟，可注入以便 JVM 单测（墙钟回拨会让差值恒负、停滞检测与失败判定同时失效）。 */
    private val clock: () -> Long = { MonoClock.now() }
) {

    private val gson = Gson()

    /** 发送回调：由 Application 注入（reqId/startTs/endTs/acked → 构造 REQUEST 消息定向发送）。 */
    private var requestSender: ((String, String, Long, Long, List<Int>) -> Unit)? = null

    /** ACK 回调：(deviceId, reqId, seq)。 */
    private var ackSender: ((String, String, Int) -> Unit)? = null

    private val io = Executors.newSingleThreadScheduledExecutor()

    /** 当前任务状态（仅 io 线程访问）。 */
    private var current: Task? = null

    private class Task(
        val deviceId: String,
        val reqId: String,
        val startTs: Long,
        val endTs: Long,
        /** 停滞判定时钟由外层注入：Task 是嵌套类，访问不到 HistoryClient 的属性。 */
        private val clock: () -> Long
    ) {
        var total = -1
        val received = HashSet<Int>()
        var inserted = 0
        var finished = false
        var lastActivityAt = clock()
        var retries = 0
    }

    fun bindSenders(
        requestSender: (String, String, Long, Long, List<Int>) -> Unit,
        ackSender: (String, String, Int) -> Unit
    ) {
        this.requestSender = requestSender
        this.ackSender = ackSender
    }

    init {
        // 调度周期为 stallTimeoutMs/2，须 >= 1ms，否则 ScheduledExecutorService 拒绝构造
        require(stallTimeoutMs >= 2) { "stallTimeoutMs 过小: $stallTimeoutMs" }
        io.scheduleWithFixedDelay({ checkStall() }, stallTimeoutMs, stallTimeoutMs / 2, TimeUnit.MILLISECONDS)
    }

    /** 发起拉取（days 天前的数据至今）；重复发起先取消上一个任务。 */
    fun start(deviceId: String, days: Int) {
        val endTs = System.currentTimeMillis()
        val startTs = endTs - days.coerceIn(1, 30) * 24 * 60 * 60 * 1000L
        io.execute {
            cancelInternal()
            val task = Task(deviceId, UUID.randomUUID().toString(), startTs, endTs, clock)
            current = task
            sendRequest(task, emptyList())
            controllerState.setHistoryProgress(
                HistoryProgress(deviceId, task.reqId, received = 0, total = 0)
            )
        }
    }

    /** 取消当前拉取。 */
    fun cancel() {
        io.execute { cancelInternal() }
    }

    /** 关闭：取消任务并停调度/io 线程（单测 teardown 用；应用内为进程级单例无需调用）。 */
    fun close() {
        cancel()
        io.shutdown()
    }

    /** 处理受控端回传的分块（route 分发，转 io 线程）。 */
    fun onChunk(fromDeviceId: String?, payload: HistoryChunkPayload) {
        io.execute {
            val task = current ?: return@execute
            if (task.finished || task.reqId != payload.reqId || fromDeviceId != task.deviceId) return@execute
            // total 非法或 seq 越界：受控端缓存回收后重新生成出更小分块集时，
            // 已收的越大界 seq 计入 received 会提前误判完成 → 直接忽略
            if (payload.total <= 0 || payload.seq < 0 || payload.seq >= payload.total) return@execute

            task.total = payload.total
            if (payload.seq !in task.received) {
                task.received.add(payload.seq)
                task.lastActivityAt = clock()
                val inserted = runCatching { mergeChunk(task.deviceId, payload.data) }.getOrDefault(0)
                task.inserted += inserted
                ackSender?.invoke(task.deviceId, task.reqId, payload.seq)
            }
            controllerState.setHistoryProgress(
                HistoryProgress(
                    task.deviceId, task.reqId,
                    received = task.received.size, total = payload.total,
                    inserted = task.inserted,
                    done = task.received.size >= payload.total
                )
            )
            if (task.received.size >= payload.total) {
                task.finished = true
                current = null
            }
        }
    }

    /** 停滞检测：无进展重发 REQUEST（带 acked），重试上限后判失败。 */
    private fun checkStall() {
        io.execute {
            val task = current ?: return@execute
            if (task.finished) return@execute
            if (clock() - task.lastActivityAt < stallTimeoutMs) return@execute
            if (task.retries >= MAX_RETRIES) {
                task.finished = true
                current = null
                controllerState.setHistoryProgress(
                    HistoryProgress(
                        task.deviceId, task.reqId,
                        received = task.received.size, total = task.total.coerceAtLeast(0),
                        inserted = task.inserted,
                        failed = true, failedReason = "传输多次中断，请检查网络后重试"
                    )
                )
                return@execute
            }
            task.retries++
            task.lastActivityAt = clock()
            sendRequest(task, task.received.toList())
        }
    }

    private fun sendRequest(task: Task, acked: List<Int>) {
        requestSender?.invoke(task.deviceId, task.reqId, task.startTs, task.endTs, acked)
    }

    private fun cancelInternal() {
        current?.let { task ->
            task.finished = true
            if (controllerState.historyProgress?.reqId == task.reqId &&
                controllerState.historyProgress?.done != true &&
                controllerState.historyProgress?.failed != true
            ) {
                controllerState.setHistoryProgress(
                    HistoryProgress(
                        task.deviceId, task.reqId,
                        received = task.received.size, total = task.total.coerceAtLeast(0),
                        inserted = task.inserted, failed = true, failedReason = "已取消"
                    )
                )
            }
        }
        current = null
    }

    /** 解码分块（gzip+Base64 JSONL）合并入 TrackStore，返回新增点数。 */
    private fun mergeChunk(deviceId: String, data: String): Int {
        if (data.isEmpty()) return 0
        val bytes = Base64.decode(data, Base64.NO_WRAP)
        val lines = GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader().readLines()
            .filter { it.isNotBlank() }
        val points = lines.mapNotNull { line ->
            runCatching { gson.fromJson(line, TrackStore.TrackPoint::class.java) }.getOrNull()
        }
        var inserted = 0
        // 同步等待写入完成，保证进度里的 inserted 准确（TrackStore 单线程写，跨池 await 无死锁）
        val latch = java.util.concurrent.CountDownLatch(1)
        trackStore.appendPoints(deviceId, points) {
            inserted = it
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return inserted
    }

    /** 等待队列清空（仅供单测）。 */
    fun flushForTest() {
        io.submit {}.get()
    }

    companion object {
        /** 停滞判定：该时长内无新块到达则重发请求。 */
        const val STALL_TIMEOUT_MS = 15_000L

        /** 停滞重试上限，超过判失败。 */
        const val MAX_RETRIES = 6
    }
}
