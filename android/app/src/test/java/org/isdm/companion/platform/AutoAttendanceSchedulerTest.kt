package org.isdm.companion.platform

import org.isdm.companion.engine.CompanionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutoAttendanceSchedulerTest {
    @Test
    fun `schedules the next class window after the evening cutoff`() {
        val now = Instant.parse("2026-08-09T15:30:00Z") // 21:00 IST
        val session = session(
            id = "101",
            start = "2026-08-10T03:30:00Z", // 09:00 IST
            end = "2026-08-10T04:45:00Z",
        )

        val window = autoAttendanceWindows(listOf(session), now).single()

        assertEquals(Instant.parse("2026-08-10T03:20:00Z"), window.alarmAt)
        assertEquals(Instant.parse("2026-08-10T05:00:00Z"), window.stopAt)
    }

    @Test
    fun `uses a short per-session window and excludes schedule-only rows`() {
        val now = Instant.parse("2026-08-10T03:35:00Z")
        val attendance = session("101", "2026-08-10T03:30:00Z", "2026-08-10T04:45:00Z")
        val scheduleOnly = session(null, "2026-08-10T05:00:00Z", "2026-08-10T06:00:00Z")

        val windows = autoAttendanceWindows(listOf(attendance, scheduleOnly), now)

        assertEquals(1, windows.size)
        assertEquals(now.plusSeconds(3), windows.single().alarmAt)
    }

    @Test
    fun `does not schedule a class after the daily cutoff`() {
        val now = Instant.parse("2026-08-10T10:00:00Z")
        val evening = session("202", "2026-08-10T13:00:00Z", "2026-08-10T14:00:00Z")

        assertTrue(autoAttendanceWindows(listOf(evening), now).isEmpty())
    }

    @Test
    fun `does not reschedule an attendance session after it is marked`() {
        val now = Instant.parse("2026-08-10T03:35:00Z")
        val marked = session("101", "2026-08-10T03:30:00Z", "2026-08-10T04:45:00Z")
            .copy(marked = true)

        assertTrue(autoAttendanceWindows(listOf(marked), now).isEmpty())
    }

    private fun session(id: String?, start: String, end: String) = CompanionSession(
        nid = id,
        eventNid = null,
        name = "Class",
        cohort = null,
        sessionNumber = null,
        start = Instant.parse(start),
        end = Instant.parse(end),
    )
}
