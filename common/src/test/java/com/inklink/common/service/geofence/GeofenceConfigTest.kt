package com.inklink.common.service.geofence

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceConfigTest {

    private val gson = Gson()

    @Test
    fun `fromList 主围栏写旧字段`() {
        val cfg = GeofenceConfig.fromList(
            listOf(
                GeoFence(31.0, 121.0, 300.0, "家"),
                GeoFence(31.1, 121.1, 500.0, "学校")
            )
        )
        assertEquals(31.0, cfg.lat, 1e-9)
        assertEquals(121.0, cfg.lng, 1e-9)
        assertEquals(300.0, cfg.radius, 1e-9)
        assertEquals(2, cfg.fences?.size)
        assertEquals("家", cfg.fences?.get(0)?.name)
    }

    @Test
    fun `toGeoFences 优先 fences 数组`() {
        val cfg = GeofenceConfig.fromList(
            listOf(
                GeoFence(31.0, 121.0, 300.0, "家"),
                GeoFence(31.1, 121.1, 500.0, "学校")
            )
        )
        val fences = cfg.toGeoFences()
        assertEquals(2, fences.size)
        assertEquals("学校", fences[1].name)
    }

    @Test
    fun `toGeoFences 无数组时回退主围栏字段`() {
        val cfg = GeofenceConfig(31.0, 121.0, 300.0)
        val fences = cfg.toGeoFences()
        assertEquals(1, fences.size)
        assertEquals(300.0, fences[0].radiusMeters, 1e-9)
        assertNull(fences[0].name)
    }

    @Test
    fun `序列化含 fences 数组与名称`() {
        val json = gson.toJson(
            GeofenceConfig.fromList(listOf(GeoFence(31.0, 121.0, 300.0, "家")))
        )
        assertTrue(json.contains("\"fences\""))
        assertTrue(json.contains("\"name\":\"家\""))
        assertTrue(json.contains("\"lat\":31.0"))
    }

    @Test
    fun `旧格式 JSON 反序列化兼容`() {
        // 旧受控端发出的载荷（无 fences/name 字段）
        val cfg = gson.fromJson("""{"lat":31.0,"lng":121.0,"radius":300.0}""", GeofenceConfig::class.java)
        assertEquals(1, cfg.toGeoFences().size)
        assertNull(cfg.fences)
    }

    @Test
    fun `单围栏 from 保持旧字段语义`() {
        val cfg = GeofenceConfig.from(GeoFence(31.0, 121.0, 300.0))
        assertEquals(31.0, cfg.lat, 1e-9)
        assertNull(cfg.fences)
    }

    @Test
    fun `fromList 空列表抛出`() {
        var threw = false
        try {
            GeofenceConfig.fromList(emptyList())
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
