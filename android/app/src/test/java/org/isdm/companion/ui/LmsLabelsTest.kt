package org.isdm.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LmsLabelsTest {
    @Test fun prefixesAndCourseNamesArePresentationOnly() {
        val raw = "B10 - T1 - State, Market and Society"
        assertEquals("SMS", displayLmsLabel(raw))
        assertEquals("B10 - T1 - State, Market and Society", raw)
        assertEquals("PMDL - Reflection 2", displayLmsLabel("B10 - T1 - PMDL - Reflection 2"))
        assertEquals("WID_Session1_Mandatory Readings", displayLmsLabel("B10_T1_WID_Session1_Mandatory Readings"))
        assertEquals("Attendance - SMS - Session 2", displayLmsLabel("Attendance - State Market and Society - Session 2"))
        assertEquals("Research Methods", displayLmsLabel("Research Methods"))
    }
}
