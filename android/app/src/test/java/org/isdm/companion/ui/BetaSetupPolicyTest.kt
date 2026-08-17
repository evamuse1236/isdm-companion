package org.isdm.companion.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BetaSetupPolicyTest {
    @Test
    fun `automatic attendance requires consent known cohort and required system access`() {
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

        assertTrue(status.canEnableAutomaticAttendance)
        assertFalse(status.notificationsAvailable)
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
