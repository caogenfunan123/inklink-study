package com.inklink.common.service.gps

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡尔曼滤波回归测试。
 *
 * 锁定「静止强抑制 → 起步滞后」的修复：长时间静止使方差收敛到近 0，
 * 起步时必须立即放开协方差，否则滤波点要分钟级才追上真实位置
 * （围栏判定/寻宝累计在此期间全部滞后）。
 */
class KalmanLocationFilterTest {

    /** 纬度每 0.00001° 约 1.11m，取足够大的步长让测试对微小误差不敏感。 */
    @Test
    fun stationThenWalkConvergesQuickly() {
        val f = KalmanLocationFilter()
        var t = 1_000_000L
        // 60 帧静止：滤波点收敛到初始位置附近
        repeat(60) { f.filter(31.2304, 121.4737, accuracy = 5f, speed = 0.1f, timestamp = t); t += 1000 }
        // 起步：沿纬度每帧 +0.00002°（约 2.2m），连续走 30 帧
        var lat = 31.2304
        repeat(30) { lat += 0.00002; f.filter(lat, 121.4737, accuracy = 5f, speed = 1.5f, timestamp = t); t += 1000 }
        val (outLat, outLng) = f.filter(lat, 121.4737, accuracy = 5f, speed = 1.5f, timestamp = t)
        val expectLat = 31.2304 + 0.00002 * 30
        // 误差放宽到约 1.2m：修复前每帧只追 5mm，30 帧后误差仍在米级
        assertTrue(
            "滤波点滞后真实位置 ${kotlin.math.abs(outLat - expectLat) * 111_000}m",
            kotlin.math.abs(outLat - expectLat) < 0.000011
        )
        assertTrue("静止方向不应漂移", kotlin.math.abs(outLng - 121.4737) < 1e-6)
    }

    @Test
    fun stationarySuppressesJitter() {
        val f = KalmanLocationFilter()
        var t = 1_000_000L
        f.filter(31.2304, 121.4737, accuracy = 5f, speed = 0.1f, timestamp = t)
        // 多径噪声 ±3m 抖动，速度恒为 0：滤波点应留在初始位置 1m 内
        var last = 31.2304 to 121.4737
        repeat(50) { i ->
            val jitter = if (i % 2 == 0) 0.000027 else -0.000027 // ~±3m
            last = f.filter(31.2304 + jitter, 121.4737, accuracy = 30f, speed = 0.1f, timestamp = t)
            t += 1000
        }
        assertTrue(
            "静止抑制失效，漂移 ${kotlin.math.abs(last.first - 31.2304) * 111_000}m",
            kotlin.math.abs(last.first - 31.2304) < 0.000009
        )
    }
}
