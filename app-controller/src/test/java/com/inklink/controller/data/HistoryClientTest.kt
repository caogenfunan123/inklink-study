package com.inklink.controller.data

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.inklink.controller.state.ControllerState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
class HistoryClientTest {

    private lateinit var context: Context
    private lateinit var store: TrackStore
    private lateinit var state: ControllerState
    private lateinit var client: HistoryClient
    private val gson = Gson()

    private val requests = mutableListOf<HistoryRequest>()

    private data class HistoryRequest(
        val deviceId: String,
        val reqId: String,
        val startTs: Long,
        val endTs: Long,
        val acked: List<Int>
    )

    private val base = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "tracks").deleteRecursively()
        store = TrackStore(context)
        state = ControllerState()
        client = HistoryClient(store, state, stallTimeoutMs = 200)
        requests.clear()
        client.bindSenders(
            requestSender = { deviceId, reqId, startTs, endTs, acked ->
                requests.add(HistoryRequest(deviceId, reqId, startTs, endTs, acked))
            },
            ackSender = { _, _, _ -> }
        )
    }

    @After
    fun tearDown() {
        File(context.filesDir, "tracks").deleteRecursively()
    }

    /** 当前任务的 reqId（start 后由 HistoryClient 生成的随机 UUID）。 */
    private val reqId: String
        get() = requests.first().reqId

    private fun gzipB64(text: String): String {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun chunk(seq: Int, total: Int, lines: List<String>) =
        com.inklink.common.protocol.payload.HistoryChunkPayload(
            reqId = reqId, seq = seq, total = total,
            data = if (lines.isEmpty()) "" else gzipB64(lines.joinToString("\n"))
        )

    private fun pointJson(ts: Long, lat: Double) =
        gson.toJson(TrackStore.TrackPoint(ts, lat, 121.0, 1f, 10f))

    @Test
    fun `start 发请求并收到全部块后完成`() {
        client.start("dev-1", 3)
        client.flushForTest()
        assertEquals(1, requests.size)

        client.onChunk("dev-1", chunk(0, 2, listOf(pointJson(base, 31.0))))
        client.onChunk("dev-1", chunk(1, 2, listOf(pointJson(base + 5_000, 31.1))))
        client.flushForTest()

        val progress = state.historyProgress!!
        assertTrue(progress.done)
        assertEquals(2, progress.received)
        assertEquals(2, progress.inserted)
        assertFalse(progress.failed)
        assertEquals(2, store.totalPoints("dev-1"))
    }

    @Test
    fun `重复块不重复计数与写入`() {
        client.start("dev-1", 1)
        client.flushForTest()

        client.onChunk("dev-1", chunk(0, 2, listOf(pointJson(base, 31.0))))
        client.onChunk("dev-1", chunk(0, 2, listOf(pointJson(base, 31.0))))
        client.onChunk("dev-1", chunk(1, 2, listOf(pointJson(base + 5_000, 31.1))))
        client.flushForTest()

        assertTrue(state.historyProgress!!.done)
        assertEquals(1, state.historyProgress!!.inserted)
        assertEquals(1, store.totalPoints("dev-1"))
    }

    @Test
    fun `reqId或来源不匹配的块被忽略`() {
        client.start("dev-1", 1)
        client.flushForTest()

        client.onChunk("other", chunk(0, 1, listOf(pointJson(base, 31.0))))
        client.onChunk(
            "dev-1",
            com.inklink.common.protocol.payload.HistoryChunkPayload(
                reqId = "wrong-req", seq = 0, total = 1,
                data = gzipB64(listOf(pointJson(base, 31.0)).joinToString("\n"))
            )
        )
        client.flushForTest()

        assertEquals(0, store.totalPoints("dev-1"))
        assertFalse(state.historyProgress!!.done)
    }

    @Test
    fun `空块视为完成信号且新增为零`() {
        client.start("dev-1", 1)
        client.flushForTest()

        client.onChunk("dev-1", chunk(0, 1, emptyList()))
        client.flushForTest()

        assertTrue(state.historyProgress!!.done)
        assertEquals(0, state.historyProgress!!.inserted)
    }

    @Test
    fun `停滞重发请求带已收序号`() {
        client.start("dev-1", 7)
        client.flushForTest()
        assertEquals(1, requests.size)

        client.onChunk("dev-1", chunk(0, 3, listOf(pointJson(base, 31.0))))
        client.flushForTest()

        // 等待停滞检测（stallTimeoutMs=200，周期 100ms）
        Thread.sleep(700)
        client.flushForTest()

        assertTrue(requests.size >= 2)
        val retry = requests.last()
        assertEquals(requests.first().reqId, retry.reqId)
        assertEquals(listOf(0), retry.acked)
    }

    @Test
    fun `取消任务后块被忽略`() {
        client.start("dev-1", 1)
        client.flushForTest()
        client.cancel()
        client.flushForTest()

        client.onChunk("dev-1", chunk(0, 1, listOf(pointJson(base, 31.0))))
        client.flushForTest()

        assertEquals(0, store.totalPoints("dev-1"))
        assertTrue(state.historyProgress!!.failed)
    }
}
