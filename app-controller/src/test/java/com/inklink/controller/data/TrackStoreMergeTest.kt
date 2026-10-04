package com.inklink.controller.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TrackStoreMergeTest {

    private lateinit var context: Context
    private lateinit var store: TrackStore

    private val base = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "tracks").deleteRecursively()
        store = TrackStore(context)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "tracks").deleteRecursively()
    }

    private fun point(ts: Long, lat: Double = 31.0, acc: Float = 10f) =
        TrackStore.TrackPoint(ts, lat, 121.0, 1.5f, acc)

    @Test
    fun `空合并立即回调零`() {
        var called = false
        store.appendPoints("dev-1", emptyList()) {
            called = true
            assertEquals(0, it)
        }
        assertTrue(called)
    }

    @Test
    fun `合并写入并按天分文件`() {
        store.appendPoints(
            "dev-1",
            listOf(point(base), point(base + 24 * 60 * 60 * 1000L))
        ) {}
        store.flushForTest()

        // 两个点跨天 → 两个日期文件，各 1 点
        assertEquals(2, store.listDays("dev-1").size)
        assertEquals(2, store.listDays("dev-1").sumOf { store.readDay("dev-1", it).size })
    }

    @Test
    fun `重复时间戳去重`() {
        store.appendPoints("dev-1", listOf(point(base, lat = 31.1), point(base, lat = 31.9))) {}
        store.flushForTest()
        // 同一批内重复 ts 只留首个
        assertEquals(1, store.totalPoints("dev-1"))

        // 再次合并同 ts 点不新增
        var inserted = -1
        store.appendPoints("dev-1", listOf(point(base, lat = 31.5))) { inserted = it }
        store.flushForTest()
        assertEquals(0, inserted)
        assertEquals(1, store.totalPoints("dev-1"))
    }

    @Test
    fun `与实时写入数据去重互补`() {
        // 模拟：实时在线先收了 2 个点（append），补传含 3 个点（2 重复 + 1 新）
        store.append("dev-1", com.inklink.common.service.gps.GpsReport(lat = 31.0, lng = 121.0, time = base, accuracy = 10f))
        store.append("dev-1", com.inklink.common.service.gps.GpsReport(lat = 31.1, lng = 121.0, time = base + 5_000, accuracy = 10f))
        store.flushForTest()

        var inserted = -1
        store.appendPoints(
            "dev-1",
            listOf(
                point(base, lat = 31.0),
                point(base + 5_000, lat = 31.1),
                point(base + 10_000, lat = 31.2)
            )
        ) { inserted = it }
        store.flushForTest()

        assertEquals(1, inserted)
        assertEquals(3, store.totalPoints("dev-1"))
    }

    @Test
    fun `漂移点在合并时丢弃`() {
        var inserted = -1
        store.appendPoints("dev-1", listOf(point(base, acc = 80f), point(base + 1, acc = 10f))) { inserted = it }
        store.flushForTest()
        assertEquals(1, inserted)
    }

    @Test
    fun `受控端落盘JSONL格式可直接解析合并`() {
        // 锁定两端「零转换」契约：GpsTrackLogger 落盘行的键名（t/la/lo/sp/ac）
        // 必须能被 TrackStore.TrackPoint 直接解析并并入
        val hostLine = """{"t":$base,"la":31.123456,"lo":121.654321,"sp":1.5,"ac":10.0}"""
        val parsed = Gson().fromJson(hostLine, TrackStore.TrackPoint::class.java)
        assertEquals(base, parsed.t)
        assertEquals(31.123456, parsed.la, 1e-9)
        assertEquals(121.654321, parsed.lo, 1e-9)
        assertEquals(1.5f, parsed.sp, 1e-6f)
        assertEquals(10.0f, parsed.ac, 1e-6f)

        var inserted = -1
        store.appendPoints("dev-1", listOf(parsed)) { inserted = it }
        store.flushForTest()
        assertEquals(1, inserted)
        assertEquals(1, store.totalPoints("dev-1"))
    }
}
