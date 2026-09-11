package org.isdm.companion.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class BetaSetupPolicyTest {
    @Test
    fun `guided setup requests notifications before sending the learner to background settings`() {
        assertEquals(AutoAttendancePermissionStep.PRECISE_LOCATION, nextAutoAttendancePermission(BetaSetupPermissions(false, false, false, false)))
        assertEquals(AutoAttendancePermissionStep.NOTIFICATIONS, nextAutoAttendancePermission(BetaSetupPermissions(true, false, false, false)))
        assertEquals(AutoAttendancePermissionStep.BACKGROUND_LOCATION, nextAutoAttendancePermission(BetaSetupPermissions(true, false, false, true)))
        assertEquals(AutoAttendancePermissionStep.EXACT_ALARMS, nextAutoAttendancePermission(BetaSetupPermissions(true, true, false, true)))
        assertEquals(AutoAttendancePermissionStep.COMPLETE, nextAutoAttendancePermission(BetaSetupPermissions(true, true, true, true)))
    }
    @Test
    fun `schedule setup banner remains for needs attention and off states`() {
        assertTrue(shouldShowScheduleSetupBanner(setupReady = false, autoAttendanceEnabled = false))
        assertTrue(shouldShowScheduleSetupBanner(setupReady = true, autoAttendanceEnabled = false))
        assertFalse(shouldShowScheduleSetupBanner(setupReady = true, autoAttendanceEnabled = true))
    }

    @Test
    fun `automatic attendance stays unavailable when notifications are off`() {
        val status = betaSetupStatus(
            consented = true,
            profileKnown = true,
            permissions = BetaSetupPermissions(
                preciseLocation = true,
                backgroundLocation = true,
                exactAlarms = true,
                notifications = false,
            ),
        )

        assertFalse(status.canEnableAutomaticAttendance)
        assertFalse(status.notificationsAvailable)
    }

    @Test
    fun `while using location alone never completes automatic attendance setup`() {
        val status = betaSetupStatus(
            consented = true,
            profileKnown = true,
            permissions = BetaSetupPermissions(true, false, true, true),
        )

        assertFalse(status.canEnableAutomaticAttendance)
    }

    @Test
    fun `automatic attendance is ready only after every permission is granted`() {
        val status = betaSetupStatus(
            consented = true,
            profileKnown = true,
            permissions = BetaSetupPermissions(true, true, true, true),
        )

        assertTrue(status.canEnableAutomaticAttendance)
    }

    @Test
    fun `unknown cohort needs attention even when permissions are ready`() {
        val status = betaSetupStatus(
            consented = true,
            profileKnown = false,
            permissions = BetaSetupPermissions(true, true, true, true),
        )

        assertFalse(status.canEnableAutomaticAttendance)
    }

}
