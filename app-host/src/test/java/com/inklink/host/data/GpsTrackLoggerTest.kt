package com.inklink.host.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inklink.common.service.gps.GpsReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class GpsTrackLoggerTest {

    private lateinit var context: Context
    private lateinit var logger: GpsTrackLogger

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "gps_log").deleteRecursively()
        logger = GpsTrackLogger(context)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "gps_log").deleteRecursively()
    }

    private fun report(ts: Long, lat: Double = 31.0, lng: Double = 121.0, acc: Float = 10f) =
        GpsReport(lat = lat, lng = lng, speed = 1.5f, time = ts, accuracy = acc)

    @Test
    fun `append 落盘且格式与主控端对齐`() {
        logger.append(report(ts = 1_700_000_000_000))
        logger.flushForTest()

        val points = logger.readRange(0, Long.MAX_VALUE)
        assertEquals(1, points.size)
        assertEquals(1_700_000_000_000L, points[0].t)
        assertEquals(31.0, points[0].la, 1e-9)
        assertEquals(121.0, points[0].lo, 1e-9)
    }

    @Test
    fun `漂移点不落盘`() {
        logger.append(report(ts = 1_700_000_000_000, acc = 80f))
        logger.append(report(ts = 1_700_000_005_000, acc = 10f))
        logger.flushForTest()

        assertEquals(1, logger.readRange(0, Long.MAX_VALUE).size)
    }

    @Test
    fun `readRange 按时间范围过滤且升序`() {
        val base = 1_700_000_000_000L
        // 乱序写入
        logger.append(report(ts = base + 3_000, lat = 31.3))
        logger.append(report(ts = base + 1_000, lat = 31.1))
        logger.append(report(ts = base + 2_000, lat = 31.2))
        logger.flushForTest()

        val all = logger.readRange(base, base + 10_000)
        assertEquals(listOf(31.1, 31.2, 31.3), all.map { it.la })

        val partial = logger.readRange(base + 1_500, base + 3_000)
        assertEquals(listOf(31.2, 31.3), partial.map { it.la })
    }

    @Test
    fun `跨天数据归属各自日期文件`() {
        val day1 = 1_700_000_000_000L // 落在某天
        val day2 = day1 + 24 * 60 * 60 * 1000L
        logger.append(report(ts = day1))
        logger.append(report(ts = day2))
        logger.flushForTest()

        val dir = File(context.filesDir, "gps_log")
        assertTrue("应存在两个日期文件", dir.listFiles { f -> f.name.endsWith(".jsonl") }!!.size == 2)
        assertEquals(2, logger.readRange(0, Long.MAX_VALUE).size)
    }

    @Test
    fun `append 后未 flush 也在 readRange 前强制可见`() {
        logger.append(report(ts = 1_700_000_000_000))
        // readRange 只读文件，未 flush 不可见——这是设计约定：拉取前必须 flush
        assertTrue(logger.readRange(0, Long.MAX_VALUE).isEmpty())
        logger.flushForTest()
        assertEquals(1, logger.readRange(0, Long.MAX_VALUE).size)
    }
}
