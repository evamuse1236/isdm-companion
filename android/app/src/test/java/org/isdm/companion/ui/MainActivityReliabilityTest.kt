package org.isdm.companion.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityReliabilityTest {
    @Test
    fun `launcher alias cannot inject an attendance action`() {
        org.junit.Assert.assertTrue(isTrustedAttendanceIntentTarget("org.isdm.companion.ui.MainActivity"))
        org.junit.Assert.assertFalse(isTrustedAttendanceIntentTarget("org.isdm.companion.LauncherActivity"))
    }

    @Test
    fun `assessment due label uses the activity due date`() {
        assertEquals("Due Thu, 20 Aug", formatAssessmentDueDate(LocalDate.of(2026, 8, 20)))
        assertEquals("Due date not posted", formatAssessmentDueDate(null))
    }
}
