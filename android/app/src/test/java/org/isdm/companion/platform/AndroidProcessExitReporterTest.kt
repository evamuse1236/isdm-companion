package org.isdm.companion.platform

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidProcessExitReporterTest {
    @Test
    fun `process exit reasons distinguish crashes anrs and normal exits`() {
        assertEquals("crash", processExitReasonLabel(ApplicationExitInfo.REASON_CRASH))
        assertEquals("anr", processExitReasonLabel(ApplicationExitInfo.REASON_ANR))
        assertEquals("user_requested", processExitReasonLabel(ApplicationExitInfo.REASON_USER_REQUESTED))
    }
}
