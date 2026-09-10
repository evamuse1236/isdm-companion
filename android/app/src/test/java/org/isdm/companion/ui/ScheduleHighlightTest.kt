package org.isdm.companion.ui

import java.time.Instant
import java.time.LocalDate
import org.isdm.companion.domain.SessionState
import org.isdm.companion.engine.CompanionSession
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleHighlightTest {
    private val today = LocalDate.parse("2026-08-16")

    @Test
    fun `past dates never advertise a completed class as up next`() {
        val yesterday = session("Past class", "2026-08-15T04:30:00Z", "2026-08-15T06:00:00Z")
        assertEquals(
            emptyList<ScheduleHighlight>(),
            scheduleHighlights(listOf(yesterday), today.minusDays(1), today, Instant.parse("2026-08-16T05:00:00Z")),
        )
    }

    @Test
    fun `today session stays highlighted from its start until its end`() {
        val session = session(
            name = "Public Policy",
            start = "2026-08-16T04:30:00Z",
            end = "2026-08-16T06:00:00Z",
        )

        val highlights = scheduleHighlights(
            sessions = listOf(session),
            selectedDate = today,
            today = today,
            now = Instant.parse("2026-08-16T05:45:00Z"),
        )

        assertEquals(listOf(ScheduleHighlight(session, ScheduleHighlightKind.HAPPENING_NOW)), highlights)
    }

    @Test
    fun `today highlights the earliest future session when nothing is active`() {
        val later = session(
            name = "Later",
            start = "2026-08-16T08:30:00Z",
            end = "2026-08-16T10:00:00Z",
        )
        val next = session(
            name = "Next",
            start = "2026-08-16T06:30:00Z",
            end = "2026-08-16T08:00:00Z",
        )

        val highlights = scheduleHighlights(
            sessions = listOf(later, next),
            selectedDate = today,
            today = today,
            now = Instant.parse("2026-08-16T05:45:00Z"),
        )

        assertEquals(listOf(ScheduleHighlight(next, ScheduleHighlightKind.UP_NEXT)), highlights)
    }

    @Test
    fun `another selected date highlights its first session`() {
        val selectedDate = today.plusDays(1)
        val later = session(
            name = "Later tomorrow",
            start = "2026-08-17T08:30:00Z",
            end = "2026-08-17T10:00:00Z",
        )
        val first = session(
            name = "First tomorrow",
            start = "2026-08-17T04:30:00Z",
            end = "2026-08-17T06:00:00Z",
        )

        val highlights = scheduleHighlights(
            sessions = listOf(later, first),
            selectedDate = selectedDate,
            today = today,
            now = Instant.parse("2026-08-16T05:45:00Z"),
        )

        assertEquals(listOf(ScheduleHighlight(first, ScheduleHighlightKind.UP_NEXT)), highlights)
    }

    private fun session(name: String, start: String, end: String) = CompanionSession(
        nid = null,
        eventNid = null,
        name = name,
        cohort = null,
        sessionNumber = null,
        start = Instant.parse(start),
        end = Instant.parse(end),
        state = SessionState.UPCOMING,
    )
}
