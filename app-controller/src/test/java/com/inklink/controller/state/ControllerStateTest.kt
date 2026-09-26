package com.inklink.controller.state

import com.inklink.common.service.gps.GpsReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ControllerStateTest {

    @Test
    fun setGps_aggregatesByDevice() {
        val state = ControllerState()
        val now = System.currentTimeMillis()
        val gpsA = GpsReport(31.0, 121.0, 0f, now)
        val gpsB = GpsReport(32.0, 122.0, 0f, now)

        state.setGps("deviceA", gpsA)
        state.setGps("deviceB", gpsB)

        assertEquals(2, state.gpsByDevice.size)
        assertEquals(gpsA, state.gpsByDevice["deviceA"])
        assertEquals(gpsB, state.gpsByDevice["deviceB"])
    }

    @Test
    fun setGps_updatesLatestGps() {
        val state = ControllerState()
        val gps = GpsReport(31.0, 121.0, 0f, System.currentTimeMillis())
        state.setGps("deviceA", gps)
        assertEquals(gps, state.latestGps)
    }

    @Test
    fun isOnline_byTimestamp() {
        val state = ControllerState()
        state.setGps("deviceA", GpsReport(31.0, 121.0, 0f, System.currentTimeMillis()))
        assertTrue(state.isOnline("deviceA"))
        assertFalse(state.isOnline("unknown"))
    }

    @Test
    fun selectDevice_updatesSelection() {
        val state = ControllerState()
        state.selectDevice("deviceA")
        assertEquals("deviceA", state.selectedDeviceId)
        state.selectDevice(null)
        assertNull(state.selectedDeviceId)
    }
}
