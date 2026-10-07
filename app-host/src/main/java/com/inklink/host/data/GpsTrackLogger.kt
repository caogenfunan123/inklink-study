package com.inklink.host.data

import android.content.Context
import com.google.gson.Gson
import com.inklink.common.service.gps.GpsReport
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 受控端轨迹落盘（离线补传数据源）。
 *
 * - 目录：filesDir/gps_log/<yyyyMMdd>.jsonl（受控端只记录自身轨迹），
 *   每行一条 [TrackPoint] JSON，格式与主控端 TrackStore.TrackPoint 完全一致，
 *   主控端拉取后可直接按时间戳去重合并，零转换。
 * - 写入挂在上报节流点（8m/3s），静止不写；低质量点（accuracy 上限）丢弃。
 * - 内存按天分桶 buffer + 定时 flush，避免秒级 GPS 回调高频开文件写盘；
 *   跨天边界按点自身时间戳归属日期文件；服务销毁强制 flush。
 * - 超过 [RETENTION_DAYS] 天的文件启动时滚动清理。
 */
class GpsTrackLogger(context: Context) {

    /** 单个轨迹点（键名与主控端 TrackStore.TrackPoint 对齐：首字母缩写控制体积）。 */
    data class TrackPoint(
        val t: Long,
        val la: Double,
        val lo: Double,
        val sp: Float,
        val ac: Float
    )

    private val root = File(context.filesDir, "gps_log")
    private val gson = Gson()

    /** 按天分桶的写入缓冲：day(yyyyMMdd) -> 行文本。 */
    private val buffer = LinkedHashMap<String, StringBuilder>()
    private val lock = Any()

    /** 单线程串行写，避免并发追加错行；定时 flush 需调度能力。 */
    private val io = Executors.newSingleThreadScheduledExecutor()

    init {
        root.mkdirs()
        cleanup()
        io.scheduleWithFixedDelay({ flushInternal() }, FLUSH_INTERVAL_SEC, FLUSH_INTERVAL_SEC, TimeUnit.SECONDS)
    }

    /** 追加一个轨迹点（进内存 buffer；低质量点直接丢弃）。 */
    fun append(report: GpsReport) {
        if (report.accuracy > MAX_ACCURACY_METERS) return
        val day = dayFormat(report.time)
        val line = gson.toJson(TrackPoint(report.time, report.lat, report.lng, report.speed, report.accuracy)) + "\n"
        synchronized(lock) {
            buffer.getOrPut(day) { StringBuilder() }.append(line)
        }
    }

    /** 读取 [startTs, endTs] 范围内的轨迹点（同步 IO，调用方自行放后台线程，按时间升序）。 */
    fun readRange(startTs: Long, endTs: Long): List<TrackPoint> {
        val out = mutableListOf<TrackPoint>()
        if (endTs < startTs) return out
        // 钳制末端：day += DAY_MILLIS 越过 Long.MAX_VALUE 会回绕成负值导致死循环；
        // 同时限制最大扫描天数，防止调用方传入极端区间时逐天文件探测失控
        val safeEnd = endTs.coerceAtMost(Long.MAX_VALUE - DAY_MILLIS)
        var day = startTs
        var scanDays = 0
        while (day <= safeEnd && scanDays <= MAX_SCAN_DAYS) {
            val f = File(root, "${dayFormat(day)}.jsonl")
            if (f.isFile) {
                // cleanup 线程可能并发删除该文件（30 天保留期），读取失败按无数据处理
                val lines = runCatching { f.readLines() }.getOrNull() ?: emptyList()
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    runCatching { gson.fromJson(line, TrackPoint::class.java) }.getOrNull()
                        ?.takeIf { it.t in startTs..endTs }
                        ?.let { out.add(it) }
                }
            }
            day += DAY_MILLIS
            scanDays++
        }
        return out.sortedBy { it.t }
    }

    /** 立即把内存 buffer 落盘（拉取历史前调用，保证数据完整）。 */
    fun flush() {
        io.execute { flushInternal() }
        io.submit {}.get()
    }

    private fun flushInternal() {
        val pending: Map<String, String> = synchronized(lock) {
            if (buffer.isEmpty()) return
            val snapshot = buffer.mapValues { it.value.toString() }
            buffer.clear()
            snapshot
        }
        runCatching {
            pending.forEach { (day, lines) ->
                if (lines.isNotBlank()) {
                    File(root, "$day.jsonl").appendText(lines)
                }
            }
        }
    }

    /** 删除超过保留期的轨迹文件。 */
    private fun cleanup() {
        io.execute {
            runCatching {
                val cutoff = System.currentTimeMillis() - RETENTION_DAYS * DAY_MILLIS
                root.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.forEach { f ->
                    val day = f.name.removeSuffix(".jsonl")
                    if (day.matches(DAY_PATTERN)) {
                        val t = runCatching { dayFormatterThreadLocal.get().parse(day)?.time }.getOrNull()
                        if (t != null && t < cutoff) f.delete()
                    }
                }
            }
        }
    }

    private fun dayFormat(time: Long): String = dayFormatterThreadLocal.get().format(Date(time))

    /** 等待后台写队列清空并强制 flush（仅供单测确定性断言）。 */
    fun flushForTest() {
        flush()
    }

    /** 关闭：强制落盘并关停写线程（服务销毁/单测 teardown 调用，防线程泄漏）。 */
    fun close() {
        runCatching { flush() }
        io.shutdown()
    }

    companion object {
        /** accuracy 高于该值（米）的点视为漂移点，不落盘（与主控端 TrackStore 一致）。 */
        const val MAX_ACCURACY_METERS = 50f

        /** 轨迹文件保留天数。 */
        const val RETENTION_DAYS = 30L
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

        /** readRange 单次扫描的天数上限（约 100 年，覆盖全部合法 epoch 区间）。 */
        private const val MAX_SCAN_DAYS = 36_600
        private const val FLUSH_INTERVAL_SEC = 30L
        private val DAY_PATTERN = Regex("\\d{8}")

        /**
         * 线程本地日期格式器。
         *
         * SimpleDateFormat 非线程安全，而 dayFormat 会被 GPS 回调线程、flush io 线程、
         * 历史读线程、清理线程并发调用：内部 Calendar 并发轻则产出错误日期字符串
         * （轨迹进错文件 = 整页轨迹消失），重则抛 ArrayIndexOutOfBoundsException。
         */
        private val dayFormatterThreadLocal = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd", Locale.US) }
    }
}
