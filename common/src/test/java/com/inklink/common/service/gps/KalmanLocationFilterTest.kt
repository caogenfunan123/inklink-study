package com.inklink.common.service.gps

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡尔曼滤波回归测试。
 *
 * 1. `stationarySuppressesJitter`：静止强抑制（±3m 多径抖动漂移 < 1m）。
 * 2. `movingReopensCovarianceImmediately`：静止收敛后方差≈0.05㎡，若不在
 *    "静止→移动"跃迁时放开协方差，首帧只追 4mm；放开后首帧即追半个步长。
 * 3. `walkAfterStationaryStaysWithinNoiseFloor`：5m 测量噪声 + 1Hz 下的
 *    稳态滞后有界（约 10m），步行整段不掉队。
 */
class KalmanLocationFilterTest {

    /** 纬度每 0.00001° 约 1.11m。 */
    private val step = 0.00002 // 约 2.2m/帧

    private fun filter(f: KalmanLocationFilter, lat: Double, speed: Float, t: Long) =
        f.filter(lat, 121.4737, accuracy = 5f, speed = speed, timestamp = t)

    @Test
    fun movingReopensCovarianceImmediately() {
        val f = KalmanLocationFilter()
        var t = 1_000_000L
        // 60 帧静止：方差收敛，k≈0.002（不放开协方差时首帧只追 4mm）
        repeat(60) { filter(f, 31.2304, speed = 0.1f, t); t += 1000 }
        val (startLat, _) = filter(f, 31.2304, speed = 0.1f, t); t += 1000

        val raw = 31.2304 + step
        val (outLat, _) = filter(f, raw, speed = 1.5f, t)
        val movedM = abs(outLat - startLat) * 111_000
        val gapM = abs(outLat - raw) * 111_000
        assertTrue("起步首帧应追回半程，实际只追上 ${movedM}m", movedM > 0.8)
        assertTrue("起步首帧滞后 ${gapM}m 过大", gapM < 1.5)
    }

    @Test
    fun walkAfterStationaryStaysWithinNoiseFloor() {
        val f = KalmanLocationFilter()
        var t = 1_000_000L
        repeat(60) { filter(f, 31.2304, speed = 0.1f, t); t += 1000 }
        var lat = 31.2304
        var out = lat to 121.4737
        repeat(30) { lat += step; out = filter(f, lat, speed = 1.5f, t); t += 1000 }

        val errM = abs(out.first - lat) * 111_000
        // 5m 测量噪声下的稳态滞后上界（实测约 10m）
        assertTrue("步行 30 帧后滞后真实位置 ${errM}m", errM < 15)
        assertTrue("静止方向不应漂移", abs(out.second - 121.4737) < 1e-6)
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
            "静止抑制失效，漂移 ${abs(last.first - 31.2304) * 111_000}m",
            abs(last.first - 31.2304) < 0.000009
        )
    }
}
