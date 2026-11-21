package com.thrust.revreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {

    @Test
    fun petrolCar_oneMonitorNotReady() {
        val r = parseReadiness(listOf(0x82, 0x07, 0x65, 0x04))!!
        assertFalse(r.diesel)
        assertEquals(7, r.monitors.size)
        assertEquals(listOf("Evaporative system"), r.notReady.map { it.name })
        assertFalse(r.allReady)
    }

    @Test
    fun dieselCar_usesDieselNames() {
        val r = parseReadiness(listOf(0x00, 0x1D, 0xC8, 0x40))!!
        assertTrue(r.diesel)
        assertEquals(
            listOf("Misfire", "PM filter"),
            r.notReady.map { it.name }
        )
        assertEquals(5, r.monitors.size)
    }

    @Test
    fun allReady() {
        val r = parseReadiness(listOf(0x00, 0x07, 0x65, 0x00))!!
        assertTrue(r.allReady)
    }

    @Test
    fun tooShort_returnsNull() {
        assertNull(parseReadiness(listOf(0x00, 0x07)))
    }
}
