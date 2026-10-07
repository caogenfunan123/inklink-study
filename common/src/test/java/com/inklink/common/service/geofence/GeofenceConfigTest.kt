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

    @Test
    fun `toGeoFencesOrNull 拒绝缺 radius 的载荷`() {
        // Gson 不走 Kotlin 构造默认值：缺 radius 的 JSON 反序列化出 0.0，
        // 直接构造 GeoFence 会抛异常（曾因此打死传输回调线程）
        val cfg = gson.fromJson("""{"lat":31.0,"lng":121.0}""", GeofenceConfig::class.java)
        assertNull(cfg.toGeoFencesOrNull())
    }

    @Test
    fun `toGeoFencesOrNull 拒绝 radius 为 0 与越界坐标`() {
        val zeroRadius = gson.fromJson(
            """{"lat":31.0,"lng":121.0,"radius":0.0,"fences":[{"lat":31.0,"lng":121.0,"radius":0.0}]}""",
            GeofenceConfig::class.java
        )
        assertNull(zeroRadius.toGeoFencesOrNull())
        val outOfRange = gson.fromJson(
            """{"lat":31.0,"lng":121.0,"radius":200.0,"fences":[{"lat":91.0,"lng":121.0,"radius":200.0}]}""",
            GeofenceConfig::class.java
        )
        assertNull(outOfRange.toGeoFencesOrNull())
    }

    @Test
    fun `toGeoFencesOrNull 放行合法载荷`() {
        val cfg = gson.fromJson(
            """{"lat":31.0,"lng":121.0,"radius":200.0,"fences":[{"lat":31.0,"lng":121.0,"radius":200.0,"name":"家"}]}""",
            GeofenceConfig::class.java
        )
        val fences = cfg.toGeoFencesOrNull()
        assertEquals(1, fences?.size)
        assertEquals("家", fences?.get(0)?.name)
    }
}
