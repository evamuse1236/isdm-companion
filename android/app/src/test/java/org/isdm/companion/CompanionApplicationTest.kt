package org.isdm.companion

import java.time.Instant
import java.time.LocalDate
import org.isdm.companion.engine.CachedSchedule
import org.isdm.companion.platform.StoredCredentials
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionApplicationTest {
    @Test
    fun `process startup selects the saved account before restoring attendance alarms`() {
        val calls = mutableListOf<String>()
        val cached = CachedSchedule(
            start = LocalDate.parse("2026-08-15"),
            endExclusive = LocalDate.parse("2026-08-16"),
            sessions = emptyList(),
            syncedAt = Instant.parse("2026-08-15T03:30:00Z"),
        )

        restoreAutoAttendanceOnProcessStart(
            enabled = true,
            setupReady = true,
            credentials = StoredCredentials("student@example.com", "secret"),
            selectAccount = { calls += "select:$it" },
            loadSchedule = { calls += "load"; cached },
            schedule = { calls += "schedule:${it.sessions.size}" },
        )

        assertEquals(listOf("select:student@example.com", "load", "schedule:0"), calls)
    }

    @Test
    fun `process startup does not restore attendance before updated setup is ready`() {
        val calls = mutableListOf<String>()

        restoreAutoAttendanceOnProcessStart(
            enabled = true,
            setupReady = false,
            credentials = StoredCredentials("student@example.com", "secret"),
            selectAccount = { calls += "select:$it" },
            loadSchedule = { calls += "load"; null },
            schedule = { calls += "schedule" },
        )

        assertEquals(emptyList<String>(), calls)
    }
}
