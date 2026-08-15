package org.isdm.companion.platform

import org.isdm.companion.engine.CompanionSession
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class CompanionSyncWorkerTest {
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
