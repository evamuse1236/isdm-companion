package org.isdm.companion.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesPolicyTest {
    @Test
    fun `an existing install sees release notes after an update`() {
        assertTrue(
            shouldShowReleaseNotes(
                lastSeenVersionCode = null,
                currentVersionCode = 4,
                firstInstallTime = 100,
                lastUpdateTime = 200,
            ),
        )
    }

    @Test
    fun `a fresh install does not describe itself as an update`() {
        assertFalse(
            shouldShowReleaseNotes(
                lastSeenVersionCode = null,
                currentVersionCode = 4,
                firstInstallTime = 100,
                lastUpdateTime = 100,
            ),
        )
    }

    @Test
    fun `release notes appear once per newer version`() {
        assertTrue(shouldShowReleaseNotes(3, 4, 100, 100))
        assertFalse(shouldShowReleaseNotes(4, 4, 100, 200))
    }
}
