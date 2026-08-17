package org.isdm.companion.platform

import org.isdm.companion.engine.CompanionSession
import org.isdm.companion.engine.EngineError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class CompanionSyncWorkerTest {
    @Test
    fun `background sync retries transient failures only three times`() {
        val error = EngineError.NetworkFailure("temporary")

        assertTrue(shouldRetryBackgroundSync(error, runAttemptCount = 0))
        assertTrue(shouldRetryBackgroundSync(error, runAttemptCount = 2))
        assertFalse(shouldRetryBackgroundSync(error, runAttemptCount = 3))
        assertFalse(shouldRetryBackgroundSync(EngineError.AuthenticationFailed("logged out"), runAttemptCount = 0))
    }

    @Test
    fun `reading sync waits until the current class guard has ended`() {
        val now = Instant.parse("2026-08-14T04:50:00Z")
        val session = CompanionSession(
            nid = "101",
            eventNid = null,
            name = "Class",
            cohort = null,
            sessionNumber = null,
            start = Instant.parse("2026-08-14T04:30:00Z"),
            end = Instant.parse("2026-08-14T05:30:00Z"),
        )

        assertEquals(
            Duration.ofMinutes(70),
            readingSyncDeferralDelay(listOf(session), monitorActive = false, now = now),
        )
    }

    @Test
    fun `reading sync defers again when monitoring is still active`() {
        val later = Instant.parse("2026-08-14T06:00:00Z")

        assertEquals(
            Duration.ofMinutes(30),
            readingSyncDeferralDelay(emptyList(), monitorActive = true, now = later),
        )
    }
}
