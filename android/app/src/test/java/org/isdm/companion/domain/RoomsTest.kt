package org.isdm.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomsTest {
    @Test
    fun keepsBuiltInDefaultsAndNormalisesRoomNames() {
        assertEquals(3.0, floorFor("Sahyog"))
        assertEquals(6.0, floorFor("  MAJLIS  "))
        assertEquals("Floor 3", floorLabel("sahyog"))
        assertEquals("Floor 6", floorLabel("Majlis"))
    }

    @Test
    fun parsesOverridesAndGroundFloor() {
        val floors = parseRoomFloors("Sahyog:4,Aangan:1,Ground:0")

        assertEquals(4.0, floorFor("SAHYOG", floors))
        assertEquals(1.0, floorFor("Aangan", floors))
        assertEquals("Ground floor", floorLabel("Ground", floors))
        assertNull(floorFor("Unknown", floors))
    }

    @Test
    fun ignoresMalformedOrNonFiniteOverrides() {
        val floors = parseRoomFloors("NoColon,Majlis:not-a-number,Also:Infinity,Blank:")

        assertEquals(6.0, floorFor("Majlis", floors))
        assertEquals("Ground floor", floorLabel("Blank", floors))
    }

    @Test
    fun formatsFiniteFractionalFloorsWithoutAddingAnIntegerSuffix() {
        val floors = parseRoomFloors("Mezzanine:2.5")

        assertEquals(2.5, floorFor("Mezzanine", floors))
        assertEquals("Floor 2.5", floorLabel("Mezzanine", floors))
    }
}
