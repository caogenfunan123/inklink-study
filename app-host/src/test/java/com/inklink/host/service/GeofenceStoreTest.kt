package com.inklink.host.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("inklink_geofence", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun save_load_roundtrip() {
        val store = GeofenceStore(context)
        val payload = """{"lat":22.5,"lng":113.9,"radius":300.0}"""
        store.save(payload)
        assertEquals(payload, GeofenceStore(context).load())
    }

    @Test
    fun load_withoutSave_returnsNull() {
        assertNull(GeofenceStore(context).load())
    }

    @Test
    fun save_overwritesPrevious() {
        val store = GeofenceStore(context)
        store.save("""{"lat":1.0,"lng":2.0,"radius":100.0}""")
        store.save("""{"lat":3.0,"lng":4.0,"radius":200.0}""")
        assertEquals("""{"lat":3.0,"lng":4.0,"radius":200.0}""", store.load())
    }
}
