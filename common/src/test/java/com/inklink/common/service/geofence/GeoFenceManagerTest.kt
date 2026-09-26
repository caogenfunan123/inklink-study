package com.inklink.common.service.geofence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoFenceManagerTest {

    private val center = GeoFence(latitude = 31.2304, longitude = 121.4737, radiusMeters = 100.0)

    @Test
    fun `无围栏时无事件`() {
        val manager = GeoFenceManager(confirmCount = 2)
        assertNull(manager.onLocation(31.0, 121.0))
    }

    @Test
    fun `进入围栏需连续确认`() {
        val manager = GeoFenceManager(confirmCount = 2)
        manager.updateFence(center)

        // 第一次进入候选，未确认
        assertNull(manager.onLocation(31.2304, 121.4737))
        assertFalse(manager.isInside())

        // 第二次确认，触发 Enter
        val event = manager.onLocation(31.2304, 121.4737)
        assertEquals(FenceEvent.Enter(center), event)
        assertTrue(manager.isInside())
    }

    @Test
    fun `离开围栏需连续确认`() {
        val manager = GeoFenceManager(confirmCount = 2)
        manager.updateFence(center)
        manager.onLocation(31.2304, 121.4737)
        manager.onLocation(31.2304, 121.4737)

        // 离开候选
        assertNull(manager.onLocation(31.2400, 121.4800))
        // 确认离开
        val event = manager.onLocation(31.2400, 121.4800)
        assertEquals(FenceEvent.Exit(center), event)
        assertFalse(manager.isInside())
    }

    @Test
    fun `抖动采样不触发告警`() {
        val manager = GeoFenceManager(confirmCount = 2)
        manager.updateFence(center)

        // 进入候选后立即回到外部，去抖重置
        manager.onLocation(31.2304, 121.4737)
        manager.onLocation(31.2400, 121.4800)
        manager.onLocation(31.2304, 121.4737)

        assertNull(manager.onLocation(31.2400, 121.4800))
        assertFalse(manager.isInside())
    }

    @Test
    fun `haversine 距离正确性`() {
        // 同一点距离为 0
        assertEquals(0.0, GeoFenceManager.distanceMeters(31.0, 121.0, 31.0, 121.0), 0.001)
        // 约 111.3km 每纬度
        val oneDegLat = GeoFenceManager.distanceMeters(31.0, 121.0, 32.0, 121.0)
        assertEquals(111_195.0, oneDegLat, 200.0)
    }
}
