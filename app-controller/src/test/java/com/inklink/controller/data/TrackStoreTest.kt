package com.inklink.controller.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inklink.common.service.gps.GpsReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TrackStoreTest {

    private lateinit var context: Context
    private lateinit var store: TrackStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 隔离用例：清掉 Robolectric filesDir 下的轨迹与导出目录
        File(context.filesDir, "tracks").deleteRecursively()
        File(context.filesDir, "exports").deleteRecursively()
        store = TrackStore(context)
    }

    private fun report(lat: Double, lng: Double, time: Long, accuracy: Float = 10f) =
        GpsReport(lat = lat, lng = lng, speed = 1.2f, time = time, accuracy = accuracy)

    @Test
    fun append_then_readDay_roundtrip() {
        val today = TrackStoreDay.today(0)
        store.append("dev1", report(22.5, 113.9, today))
        store.append("dev1", report(22.5001, 113.9001, today + 3000))
        store.append("dev1", report(22.5002, 113.9002, today + 6000))
        store.flushForTest()

        val points = store.readDay("dev1", TrackStoreDay.name(today))
        assertEquals(3, points.size)
        assertEquals(22.5, points[0].la, 1e-9)
        assertEquals(113.9, points[0].lo, 1e-9)
        assertEquals(22.5002, points[2].la, 1e-9)
    }

    @Test
    fun append_dropsLowAccuracy() {
        val today = TrackStoreDay.today(0)
        store.append("dev1", report(22.5, 113.9, today, accuracy = 60f))
        store.append("dev1", report(22.5, 113.9, today + 3000, accuracy = 50f))
        store.flushForTest()

        val points = store.readDay("dev1", TrackStoreDay.name(today))
        assertEquals(1, points.size)
    }

    @Test
    fun append_byDeviceId_isolated() {
        val today = TrackStoreDay.today(0)
        store.append("dev1", report(22.5, 113.9, today))
        store.append("dev2", report(31.2, 121.4, today))
        store.flushForTest()

        assertEquals(1, store.readDay("dev1", TrackStoreDay.name(today)).size)
        assertEquals(1, store.readDay("dev2", TrackStoreDay.name(today)).size)
    }

    @Test
    fun listDays_sortedDescending() {
        val today = TrackStoreDay.today(0)
        val yesterday = TrackStoreDay.today(-1)
        store.append("dev1", report(22.5, 113.9, yesterday))
        store.append("dev1", report(22.5, 113.9, today))
        store.flushForTest()

        assertEquals(
            listOf(TrackStoreDay.name(today), TrackStoreDay.name(yesterday)),
            store.listDays("dev1")
        )
    }

    @Test
    fun readDay_missingDay_returnsEmpty() {
        assertTrue(store.readDay("dev1", "19990101").isEmpty())
    }

    @Test
    fun exportGpx_containsTrkptAndCreator() {
        val today = TrackStoreDay.today(0)
        store.append("dev1", report(22.5, 113.9, today))
        store.append("dev1", report(22.5001, 113.9001, today + 3000))
        store.flushForTest()

        val file = store.exportGpx("dev1", TrackStoreDay.name(today))
        assertNotNull(file)
        val xml = file!!.readText()
        assertTrue(xml.contains("creator=\"InkLink Controller\""))
        assertTrue(xml.contains("<trkpt"))
        assertTrue(xml.contains("<time>"))
        assertTrue(xml.contains("</gpx>"))
    }

    @Test
    fun exportGpx_noData_returnsNull() {
        assertNull(store.exportGpx("dev1", "19990101"))
    }

    @Test
    fun simplify_mergesClosePoints_keepsEnds() {
        // p2 距 p1 约 1.1m 应被合并；p3 约 111m 保留；p4 约 11m 保留
        val p1 = TrackStore.TrackPoint(0, 0.0, 0.0, 0f, 0f)
        val p2 = TrackStore.TrackPoint(1, 0.00001, 0.0, 0f, 0f)
        val p3 = TrackStore.TrackPoint(2, 0.001, 0.0, 0f, 0f)
        val p4 = TrackStore.TrackPoint(3, 0.0011, 0.0, 0f, 0f)

        val out = store.simplify(listOf(p1, p2, p3, p4))

        assertEquals(listOf(p1, p3, p4), out)
    }

    @Test
    fun haversine_zeroAndSmallDistance() {
        assertEquals(0.0, store.haversineMeters(22.5, 113.9, 22.5, 113.9), 1e-6)
        // 纬度 1 度 ≈ 111.2km
        assertEquals(111_200.0, store.haversineMeters(0.0, 0.0, 1.0, 0.0), 500.0)
    }

    /** 按与今天偏移天数生成当日 12:00 的时间戳与 yyyyMMdd 文件名。 */
    private object TrackStoreDay {
        fun today(offsetDays: Int): Long {
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.DAY_OF_YEAR, offsetDays)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 12)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        fun name(time: Long): String =
            java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(time))
    }
}
