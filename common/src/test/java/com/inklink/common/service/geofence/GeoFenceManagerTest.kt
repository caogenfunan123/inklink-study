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
        assertTrue(manager.onLocation(31.0, 121.0).isEmpty())
    }

    @Test
    fun `进入围栏需连续确认`() {
        val manager = GeoFenceManager(confirmCount = 2)
        manager.updateFence(center)

        // 第一次进入候选，未确认
        assertTrue(manager.onLocation(31.2304, 121.4737).isEmpty())
        assertFalse(manager.isInside())

        // 第二次确认，触发 Enter
        val events = manager.onLocation(31.2304, 121.4737)
        assertEquals(listOf(FenceEvent.Enter(center)), events)
        assertTrue(manager.isInside())
    }

    @Test
    fun `离开围栏需连续确认`() {
        val manager = GeoFenceManager(confirmCount = 2)
        manager.updateFence(center)
        manager.onLocation(31.2304, 121.4737)
        manager.onLocation(31.2304, 121.4737)

        // 离开候选
        assertTrue(manager.onLocation(31.2400, 121.4800).isEmpty())
        // 确认离开
        val events = manager.onLocation(31.2400, 121.4800)
        assertEquals(listOf(FenceEvent.Exit(center)), events)
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

        assertTrue(manager.onLocation(31.2400, 121.4800).isEmpty())
        assertFalse(manager.isInside())
    }

    @Test
    fun `多围栏独立判定`() {
        val home = GeoFence(31.2304, 121.4737, 100.0, "家")
        val school = GeoFence(31.0400, 121.3800, 150.0, "学校")
        val manager = GeoFenceManager(confirmCount = 2)
        manager.updateFences(listOf(home, school))
        assertEquals(listOf(home, school), manager.currentFences())

        // 进入家（两次确认）
        assertTrue(manager.onLocation(31.2304, 121.4737).isEmpty())
        assertEquals(listOf(FenceEvent.Enter(home)), manager.onLocation(31.2304, 121.4737))
        assertTrue(manager.isInside())

        // 同一时刻离开家且进入学校：一次采样产生两个事件
        val events = manager.onLocation(31.0400, 121.3800)
        assertEquals(listOf(FenceEvent.Exit(home), FenceEvent.Enter(school)), events)
        assertTrue(manager.isInside())

        // 离开学校
        assertEquals(listOf(FenceEvent.Exit(school)), manager.onLocation(31.0500, 121.3900))
        assertEquals(listOf(FenceEvent.Exit(school)), manager.onLocation(31.0500, 121.3900))
        assertFalse(manager.isInside())
    }

    @Test
    fun `多围栏只影响各自去抖`() {
        val a = GeoFence(31.2304, 121.4737, 100.0)
        val b = GeoFence(31.2400, 121.4800, 100.0)
        val manager = GeoFenceManager(confirmCount = 2)
        manager.updateFences(listOf(a, b))

        // 进入 a 候选（b 无关）
        assertTrue(manager.onLocation(31.2304, 121.4737).isEmpty())
        // 在 b 内的采样不应推进 a 的去抖
        assertEquals(listOf(FenceEvent.Enter(b)), manager.onLocation(31.2400, 121.4800))
        // a 第二次确认进入
        assertEquals(listOf(FenceEvent.Enter(a)), manager.onLocation(31.2304, 121.4737))
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
