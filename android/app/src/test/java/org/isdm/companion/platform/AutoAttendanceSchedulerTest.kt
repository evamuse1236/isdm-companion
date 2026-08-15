package org.isdm.companion.platform

import org.isdm.companion.engine.CompanionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test
    fun `each overlapping alarm carries every target before monitoring starts`() {
        val now = Instant.parse("2026-08-10T03:00:00Z")
        val first = session("101", "2026-08-10T03:30:00Z", "2026-08-10T04:45:00Z")
        val second = session("202", "2026-08-10T03:30:00Z", "2026-08-10T05:00:00Z")

        val windows = autoAttendanceWindows(listOf(first, second), now)

        assertEquals(2, windows.size)
        assertTrue(windows.all { it.targetSessionIds == setOf("101", "202") })
        assertTrue(windows.all { it.stopAt == Instant.parse("2026-08-10T05:15:00Z") })
    }

    @Test
    fun `partial alarm installation rolls back every installed window`() {
        val windows = listOf(
            session("101", "2026-08-10T03:30:00Z", "2026-08-10T04:45:00Z"),
            session("202", "2026-08-10T05:00:00Z", "2026-08-10T06:00:00Z"),
        ).let { autoAttendanceWindows(it, Instant.parse("2026-08-10T03:00:00Z")) }
        val installed = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()

        val outcome = installAutoAttendanceWindowsAtomically(
            windows = windows,
            install = { window ->
                if (installed.isNotEmpty()) error("alarm manager failure")
                installed += window.requestId
            },
            cancel = { cancelled.add(it) },
        )

        assertNotNull(outcome.error)
        assertEquals(installed, cancelled)
        assertTrue(outcome.rollbackFailedIds.isEmpty())
    }

    @Test
    fun `failed alarm rollback remains tracked for later cleanup`() {
        val windows = listOf(
            session("101", "2026-08-10T03:30:00Z", "2026-08-10T04:45:00Z"),
            session("202", "2026-08-10T05:00:00Z", "2026-08-10T06:00:00Z"),
        ).let { autoAttendanceWindows(it, Instant.parse("2026-08-10T03:00:00Z")) }

        val outcome = installAutoAttendanceWindowsAtomically(
            windows = windows,
            install = { window ->
                if (window == windows.last()) error("alarm manager failure")
            },
            cancel = { false },
        )

        assertEquals(setOf(windows.first().requestId), outcome.rollbackFailedIds)
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
