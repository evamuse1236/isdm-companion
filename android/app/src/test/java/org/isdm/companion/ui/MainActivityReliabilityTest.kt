package org.isdm.companion.ui

import org.junit.Test

class MainActivityReliabilityTest {
    @Test
    fun `launcher alias cannot inject an attendance action`() {
        org.junit.Assert.assertTrue(isTrustedAttendanceIntentTarget("org.isdm.companion.ui.MainActivity"))
        org.junit.Assert.assertFalse(isTrustedAttendanceIntentTarget("org.isdm.companion.LauncherActivity"))
    }
}
