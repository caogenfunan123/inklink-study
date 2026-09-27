package com.inklink.controller.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("inklink_devices", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun add_and_list() {
        val repo = DeviceRepository(context)
        repo.add(DeviceEntity("id1", "手表1"))
        repo.add(DeviceEntity("id2", "手表2"))

        val list = repo.list()
        assertEquals(2, list.size)
        assertEquals("手表1", list.first { it.deviceId == "id1" }.nickname)
    }

    @Test
    fun add_sameDeviceId_updatesNickname() {
        val repo = DeviceRepository(context)
        repo.add(DeviceEntity("id1", "旧名"))
        repo.add(DeviceEntity("id1", "新名"))

        assertEquals(1, repo.list().size)
        assertEquals("新名", repo.list()[0].nickname)
    }

    @Test
    fun remove_deletesEntry() {
        val repo = DeviceRepository(context)
        repo.add(DeviceEntity("id1", "手表1"))
        repo.add(DeviceEntity("id2", "手表2"))

        repo.remove("id1")

        assertEquals(1, repo.list().size)
        assertNull(repo.findByDeviceId("id1"))
    }

    @Test
    fun updateNickname_updatesEntry() {
        val repo = DeviceRepository(context)
        repo.add(DeviceEntity("id1", "旧名"))

        repo.updateNickname("id1", "新名")

        assertEquals("新名", repo.findByDeviceId("id1")?.nickname)
    }

    @Test
    fun updateFence_persists() {
        val repo = DeviceRepository(context)
        repo.add(DeviceEntity("id1", "手表1"))

        repo.updateFence("id1", 22.5, 113.9, 300.0)

        val device = repo.findByDeviceId("id1")!!
        assertEquals(22.5, device.fenceLat!!, 1e-9)
        assertEquals(113.9, device.fenceLng!!, 1e-9)
        assertEquals(300.0, device.fenceRadiusM!!, 1e-9)
    }

    @Test
    fun updateFence_otherDevicesUntouched() {
        val repo = DeviceRepository(context)
        repo.add(DeviceEntity("id1", "手表1"))
        repo.add(DeviceEntity("id2", "手表2"))

        repo.updateFence("id1", 22.5, 113.9, 300.0)

        assertNull(repo.findByDeviceId("id2")?.fenceLat)
    }
}
