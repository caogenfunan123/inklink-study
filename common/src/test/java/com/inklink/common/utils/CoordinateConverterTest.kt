package com.inklink.common.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateConverterTest {

    @Test
    fun wgs84ToGcj02_offsetInChina() {
        val (lat, lng) = CoordinateConverter.wgs84ToGcj02(31.2304, 121.4737)
        assertNotEquals(31.2304, lat, 0.0001)
        assertNotEquals(121.4737, lng, 0.0001)
    }

    @Test
    fun roundTrip_withinTolerance() {
        val srcLat = 31.2304
        val srcLng = 121.4737
        val (gcjLat, gcjLng) = CoordinateConverter.wgs84ToGcj02(srcLat, srcLng)
        val (backLat, backLng) = CoordinateConverter.gcj02ToWgs84(gcjLat, gcjLng)
        assertEquals(srcLat, backLat, 0.0001)
        assertEquals(srcLng, backLng, 0.0001)
    }

    @Test
    fun outOfChina_unchanged() {
        val (lat, lng) = CoordinateConverter.wgs84ToGcj02(48.8566, 2.3522)
        assertEquals(48.8566, lat, 1e-9)
        assertEquals(2.3522, lng, 1e-9)
    }

    @Test
    fun knownOffset_directionMatches() {
        val (lat, lng) = CoordinateConverter.wgs84ToGcj02(31.2304, 121.4737)
        val dLatMeters = (lat - 31.2304) * 111_000.0
        val dLngMeters = (lng - 121.4737) * 111_000.0 * Math.cos(Math.toRadians(31.2304))
        // 上海人民广场附近：GCJ-02 相对 WGS-84 纬度南偏、经度东偏，量级约 100-500 米
        assertTrue(dLatMeters < -100.0)
        assertTrue(dLngMeters > 100.0)
    }
}
