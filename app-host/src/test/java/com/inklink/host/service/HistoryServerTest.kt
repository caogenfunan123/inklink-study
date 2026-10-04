package com.inklink.host.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.inklink.common.protocol.payload.HistoryChunkPayload
import com.inklink.common.protocol.payload.HistoryRequestPayload
import com.inklink.host.data.GpsTrackLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import android.util.Base64
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
class HistoryServerTest {

    private lateinit var context: Context
    private lateinit var logger: GpsTrackLogger
    private lateinit var server: HistoryServer
    private val gson = Gson()

    private val base = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "gps_log").deleteRecursively()
        logger = GpsTrackLogger(context)
        server = HistoryServer(logger)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "gps_log").deleteRecursively()
    }

    private fun seedPoints(count: Int) {
        repeat(count) { i ->
            logger.append(
                com.inklink.common.service.gps.GpsReport(
                    lat = 31.0 + i * 0.0001, lng = 121.0, speed = 1f,
                    time = base + i * 5_000L, accuracy = 10f
                )
            )
        }
    }

    private fun request(startTs: Long, endTs: Long, acked: List<Int>? = null) =
        HistoryRequestPayload(reqId = "req-1", startTs = startTs, endTs = endTs, acked = acked)

    private fun handle(req: HistoryRequestPayload): List<HistoryChunkPayload> {
        val sent = mutableListOf<HistoryChunkPayload>()
        server.bindSender { _, chunk -> sent.add(chunk) }
        server.handleRequest(req, target = "controller-1")
        return sent
    }

    private fun decode(chunk: HistoryChunkPayload): List<String> {
        if (chunk.data.isEmpty()) return emptyList()
        val bytes = Base64.decode(chunk.data, Base64.NO_WRAP)
        return GZIPInputStream(bytes.inputStream()).bufferedReader().readLines().filter { it.isNotBlank() }
    }

    @Test
    fun `全量分块并回传`() {
        seedPoints(95)
        val sent = handle(request(base, base + 500_000))

        assertEquals(3, sent.size) // 95 行 / 40 = 3 块
        assertTrue(sent.all { it.total == 3 })
        assertEquals(listOf(0, 1, 2), sent.map { it.seq })
        assertEquals(95, sent.sumOf { decode(it).size })
    }

    @Test
    fun `空区间返回单个空块作为完成信号`() {
        val sent = handle(request(base, base + 500_000))
        assertEquals(1, sent.size)
        assertEquals(0, sent[0].seq)
        assertTrue(sent[0].data.isEmpty())
        assertEquals(1, sent[0].total)
    }

    @Test
    fun `断点续传只补发缺失块`() {
        seedPoints(95)
        // 首次收到 seq 0 与 2，seq 1 丢失
        handle(request(base, base + 500_000, acked = listOf(0, 2)))
        val retry = handle(request(base, base + 500_000, acked = listOf(0, 2)))

        assertEquals(listOf(1), retry.map { it.seq })
        assertEquals(1, retry[0].total)
        assertEquals(40, decode(retry[0]).size)
    }

    @Test
    fun `全部已收时零补发`() {
        seedPoints(10)
        handle(request(base, base + 500_000))
        val retry = handle(request(base, base + 500_000, acked = listOf(0)))
        assertTrue(retry.isEmpty())
    }

    @Test
    fun `相同区间重复生成块内容一致`() {
        seedPoints(45)
        val first = handle(request(base, base + 500_000))
        // 清缓存模拟受控端重启（新建 HistoryServer）
        val fresh = HistoryServer(logger)
        val sent = mutableListOf<HistoryChunkPayload>()
        fresh.bindSender { _, chunk -> sent.add(chunk) }
        fresh.handleRequest(request(base, base + 500_000), target = null)

        assertEquals(first.map { it.data }, sent.map { it.data })
    }

    @Test
    fun `onAck 刷新缓存不影响数据`() {
        seedPoints(10)
        handle(request(base, base + 500_000))
        server.onAck("req-1")
        val retry = handle(request(base, base + 500_000, acked = listOf(0)))
        assertTrue(retry.isEmpty())
    }
}
