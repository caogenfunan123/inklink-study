package com.inklink.controller.data

import android.content.Context
import com.google.gson.Gson
import com.inklink.common.service.gps.GpsReport
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * 历史轨迹本地存储（按设备按天 JSONL 文件）。
 *
 * - 目录：filesDir/tracks/<deviceId>/<yyyyMMdd>.jsonl，每行一个轨迹点 JSON；
 *   追加一行即写入，读侧逐行解析并跳过坏行，无需重写整文件。
 * - 写入过滤低质量点（accuracy > [MAX_ACCURACY_METERS] 视为漂移丢弃），
 *   超过 [RETENTION_DAYS] 天的文件启动时滚动清理。
 * - GPX 1.1 导出（通用轨迹交换格式，可导入其他地图工具），供系统分享。
 *
 * 仅主控端本地存储，零协议改动；受控端保持既有 8m/3s 节流上报节奏。
 */
class TrackStore(context: Context) {

    /** 单个轨迹点（键名取首字母缩写，控制 JSONL 体积）。 */
    data class TrackPoint(
        val t: Long,
        val la: Double,
        val lo: Double,
        val sp: Float,
        val ac: Float
    )

    private val root = File(context.filesDir, "tracks")
    private val exportDir = File(context.filesDir, "exports")
    private val gson = Gson()

    /** 单线程串行写，避免并发追加错行。 */
    private val io = Executors.newSingleThreadExecutor()

    init {
        root.mkdirs()
        cleanup()
    }

    /** 追加一个上报点（异步；低质量点直接丢弃）。 */
    fun append(deviceId: String, report: GpsReport) {
        if (report.accuracy > MAX_ACCURACY_METERS) return
        io.execute {
            runCatching {
                val dir = File(root, sanitize(deviceId))
                dir.mkdirs()
                File(dir, "${dayFormat(report.time)}.jsonl")
                    .appendText(gson.toJson(TrackPoint(report.time, report.lat, report.lng, report.speed, report.accuracy)) + "\n")
            }
        }
    }

    /**
     * 批量合并轨迹点（离线补传去重写入）：按点自身时间戳分日期文件，
     * 与既有文件内时间戳重复的点跳过，返回实际新增点数（异步完成）。
     * 低质量点（accuracy 上限）与批次内重复点同样丢弃。
     */
    fun appendPoints(deviceId: String, points: List<TrackPoint>, onDone: (Int) -> Unit = {}) {
        if (points.isEmpty()) {
            onDone(0)
            return
        }
        io.execute {
            val inserted = runCatching {
                val dir = File(root, sanitize(deviceId))
                dir.mkdirs()
                var count = 0
                points.groupBy { dayFormat(it.t) }.forEach { (day, dayPoints) ->
                    val f = File(dir, "$day.jsonl")
                    val existing = if (f.isFile) {
                        f.readLines().mapNotNull { line ->
                            line.takeIf { it.isNotBlank() }?.let {
                                runCatching { gson.fromJson(it, TrackPoint::class.java) }.getOrNull()?.t
                            }
                        }.toHashSet()
                    } else {
                        HashSet()
                    }
                    val seenInBatch = HashSet<Long>()
                    val sb = StringBuilder()
                    dayPoints.forEach { p ->
                        if (p.ac > MAX_ACCURACY_METERS) return@forEach
                        if (p.t in existing || !seenInBatch.add(p.t)) return@forEach
                        sb.append(gson.toJson(p)).append('\n')
                        count++
                    }
                    if (sb.isNotEmpty()) f.appendText(sb.toString())
                }
                count
            }.getOrDefault(0)
            onDone(inserted)
        }
    }

    /** 返回有轨迹数据的日期列表（yyyyMMdd，最新在前）。 */
    fun listDays(deviceId: String): List<String> =
        File(root, sanitize(deviceId))
            .listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.map { it.name.removeSuffix(".jsonl") }
            ?.filter { it.matches(DAY_PATTERN) }
            ?.sortedDescending()
            ?: emptyList()

    /** 读取某天轨迹点（同步 IO，调用方自行放后台线程）。 */
    fun readDay(deviceId: String, day: String): List<TrackPoint> {
        val f = File(File(root, sanitize(deviceId)), "$day.jsonl")
        if (!f.isFile) return emptyList()
        return f.readLines().mapNotNull { line ->
            line.takeIf { it.isNotBlank() }?.let {
                runCatching { gson.fromJson(it, TrackPoint::class.java) }.getOrNull()
            }
        }
    }

    /** 设备全部轨迹点总数（跨天求和，仅供单测/统计，同步 IO）。 */
    fun totalPoints(deviceId: String): Int =
        listDays(deviceId).sumOf { day -> readDay(deviceId, day).size }

    /** 导出某天轨迹为 GPX 1.1 文件，返回文件；当天无数据返回 null。 */
    fun exportGpx(deviceId: String, day: String): File? {
        val points = readDay(deviceId, day)
        if (points.isEmpty()) return null
        exportDir.mkdirs()
        val out = File(exportDir, "inklink_${sanitize(deviceId)}_$day.gpx")
        out.writeText(buildGpx(points, deviceId, day))
        return out
    }

    /** 相邻点距离小于 [minMeters] 时丢弃（回放抽稀，防小屏卡顿），首尾点必保留。 */
    fun simplify(points: List<TrackPoint>, minMeters: Double = 5.0): List<TrackPoint> {
        if (points.size <= 2) return points
        val out = mutableListOf(points.first())
        for (p in points) {
            val last = out.last()
            if (haversineMeters(last.la, last.lo, p.la, p.lo) >= minMeters) out.add(p)
        }
        if (out.last() != points.last()) out[out.lastIndex] = points.last()
        return out
    }

    /** 两点球面距离（米），Haversine 公式。 */
    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun buildGpx(points: List<TrackPoint>, deviceId: String, day: String): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"InkLink Controller\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk><name>").append(sanitize(deviceId)).append(' ').append(day).append("</name><trkseg>\n")
        points.forEach { p ->
            sb.append("    <trkpt lat=\"").append(p.la).append("\" lon=\"").append(p.lo).append("\">")
                .append("<time>").append(isoFormat(p.t)).append("</time></trkpt>\n")
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    /** 删除超过保留期的轨迹文件（初始化时在 IO 线程执行一次）。 */
    private fun cleanup() {
        io.execute {
            runCatching {
                val cutoff = System.currentTimeMillis() - RETENTION_DAYS * DAY_MILLIS
                root.listFiles { f -> f.isDirectory }?.forEach { dir ->
                    dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.forEach { f ->
                        val day = f.name.removeSuffix(".jsonl")
                        if (day.matches(DAY_PATTERN)) {
                            val t = runCatching { dayFormatter.parse(day)?.time }.getOrNull()
                            if (t != null && t < cutoff) f.delete()
                        }
                    }
                }
            }
        }
    }

    /** 设备 ID 仅保留文件名安全字符，其余替换为下划线（确定性映射，读写一致）。 */
    private fun sanitize(deviceId: String): String =
        deviceId.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    private fun dayFormat(time: Long): String = dayFormatter.format(Date(time))

    private fun isoFormat(time: Long): String = isoFormatter.format(Date(time))

    /** 等待后台写队列清空（仅供单测确定性断言）。 */
    fun flushForTest() {
        io.submit {}.get()
    }

    companion object {
        /** accuracy 高于该值（米）的点视为漂移点，不落盘。 */
        const val MAX_ACCURACY_METERS = 50f

        /** 轨迹文件保留天数，超期启动时清理。 */
        const val RETENTION_DAYS = 30L
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private val DAY_PATTERN = Regex("\\d{8}")

        private val dayFormatter = SimpleDateFormat("yyyyMMdd", Locale.US)
        private val isoFormatter =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }
}
