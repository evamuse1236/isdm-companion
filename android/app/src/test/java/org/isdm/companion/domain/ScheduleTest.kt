package org.isdm.companion.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTest {
    @Test
    fun `assessment activities are not scheduled sessions`() {
        val sessions = buildSessions(
            listOf(
                event(
                    "1305879",
                    "B10 - T1 - PMDL - Reflection 2",
                    "/subtopic/view?sid=1298915&vid=1305879&cid=1298950&cat_id=70954",
                    "2026-08-17 11:37:00",
                    "2026-09-20 12:32:00",
                ),
            ),
            Cohorts(),
        )

        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `live assessment attempt URLs are not scheduled sessions`() {
        val sessions = buildSessions(
            listOf(
                event(
                    "1305877",
                    "B10 - T1 - PMDL - Reflection 2",
                    "/activity/user/attempt?nid=1305877&redirect=true",
                    "2026-08-17 11:37:00",
                    "2026-08-17 12:32:00",
                ),
            ),
            Cohorts(),
        )

        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `events longer than eight hours are not scheduled sessions`() {
        val sessions = buildSessions(
            listOf(
                event(
                    "1305877",
                    "Long-running activity",
                    "/activity/unknown",
                    "2026-08-17 11:37:00",
                    "2026-09-20 12:32:00",
                ),
            ),
            Cohorts(),
        )

        assertTrue(sessions.isEmpty())
    }

    @Test
    fun parsesAttendanceTitleIntoNameCohortAndSession() {
        assertEquals(
            ParsedTitle(name = "Maths", cohort = "Section B", session = 2),
            parseTitle("Attendance - Maths - Section B - Session 2"),
        )
        assertEquals(
            ParsedTitle(name = "spo visit", cohort = "group 3", session = 1),
            parseTitle("  spo visit - group 3 - SESSION 1  "),
        )
    }

    @Test
    fun parsesLmsWallClockInExplicitIstAndIgnoresTrailingText() {
        val expected = LocalDateTime.of(2026, 7, 29, 9, 30)
            .atZone(LMS_ZONE)
            .toInstant()

        assertEquals(expected, parseLmsTime("2026-07-29 09:30:00"))
        assertEquals(expected, parseLmsTime("2026-07-29T09:30:00+05:30 trailing"))
        assertNull(parseLmsTime("not-a-timestamp"))
    }

    @Test
    fun detectsSingleSectionAndGroupFromPersonalisedAttendanceRows() {
        val events = listOf(
            event("1", "Attendance - Maths - Section B - Session 1", "/classroom/1/view"),
            event("2", "Attendance - Shared - Section A & B - Session 1", "/classroom/2/view"),
            event("3", "Attendance - SPO - Group 3 - Session 1", "/classroom/3/view"),
            event("4", "Maths - Section A - Session 1", "/join/webinar"),
        )

        assertEquals(Cohorts(sections = setOf("B"), groups = setOf("3")), detectCohorts(events))
    }

    @Test
    fun `confirmed section narrows ambiguous LMS cohort detection`() {
        val resolved = resolveScheduleCohorts(
            detected = Cohorts(sections = setOf("A", "B")),
            confirmed = Cohorts(sections = setOf("A")),
        )
        val events = listOf(
            event("batch-a", "Digital Engagement - Section A - Session 1", "/join/webinar"),
            event("batch-b", "Digital Engagement - Section B - Session 1", "/join/webinar"),
        )

        assertEquals(setOf("A"), resolved.sections)
        assertEquals(listOf("Section A"), buildSessions(events, resolved).map { it.cohort })
    }

    @Test
    fun `confirmed section fills missing LMS cohort detection`() {
        assertEquals(
            Cohorts(sections = setOf("A")),
            resolveScheduleCohorts(
                detected = Cohorts(),
                confirmed = Cohorts(sections = setOf("A")),
            ),
        )
    }

    @Test
    fun `single unambiguous LMS section remains authoritative`() {
        assertEquals(
            Cohorts(sections = setOf("B")),
            resolveScheduleCohorts(
                detected = Cohorts(sections = setOf("B")),
                confirmed = Cohorts(sections = setOf("A")),
            ),
        )
    }

    @Test
    fun parsesCohortOverrideAndReturnsNullForBlankOrUnusableInput() {
        assertEquals(
            Cohorts(sections = setOf("A", "B"), groups = setOf("3")),
            parseCohortOverride("Section A, Section B, Group 3"),
        )
        assertNull(parseCohortOverride(""))
        assertNull(parseCohortOverride("not a cohort"))
    }

    @Test
    fun mergesAttendanceAndBatchRowsAndFiltersOtherCohorts() {
        val events = listOf(
            event("batch-a", "Digital Engagement - Section A - Session 1", "/join/webinar", "2026-07-27 14:00:00", "2026-07-27 15:30:00"),
            event("batch-b", "Digital Engagement - Section B - Session 1", "/join/webinar", "2026-07-27 14:00:00", "2026-07-27 15:30:00"),
            event(
                "attendance-b",
                "Attendance - Digital Engagement - Section B - Session 1",
                "/classroom/attendance-b/view",
                "2026-07-27 14:00:00",
                "2026-07-27 15:30:00",
                trainers = "Trainer Two",
                subject = "Digital Engagement",
            ),
            event("group-2", "SPO Visit - Group 2 - Session 1", "/join/webinar", "2026-07-31 15:00:00", "2026-07-31 16:30:00"),
            event("group-3", "SPO Visit - Group 3 - Session 1", "/join/webinar", "2026-07-31 15:00:00", "2026-07-31 16:30:00"),
            event("everyone", "Orientation", "/join/webinar", "2026-07-31 09:00:00", "2026-07-31 10:30:00"),
        )

        val sessions = buildSessions(
            events,
            Cohorts(sections = setOf("B"), groups = setOf("3")),
        )

        assertEquals(3, sessions.size)
        assertEquals(listOf("Digital Engagement", "Orientation", "SPO Visit"), sessions.map { it.name })
        val digital = sessions.first { it.name == "Digital Engagement" }
        assertEquals("attendance-b", digital.nid)
        assertEquals("batch-b", digital.eventNid)
        assertEquals("Trainer Two", digital.trainer)
        assertEquals("Digital Engagement", digital.subject)
        assertEquals("Group 3", sessions.last().cohort)
        assertTrue(sessions.none { it.cohort == "Section A" })
    }

    @Test
    fun skipsEventsWithUnparseableStartAndEstimatesAMissingEnd() {
        val events = listOf(
            event("bad", "Bad", "/join/webinar", start = "bad"),
            event("batch", "Maths - Section B - Session 2", "/join/webinar", end = null),
            event("attendance", "Attendance - Maths - Section B - Session 2", "/classroom/attendance/view", end = "2026-07-29 11:00:00"),
        )

        val row = buildSessions(events, Cohorts(sections = setOf("B"))).single()
        assertEquals("attendance", row.nid)
        assertEquals(row.start.plusSeconds(90 * 60), row.end)
        assertTrue(row.endEstimated)
    }

    @Test
    fun computesSessionStatesInPriorityOrder() {
        val start = Instant.parse("2026-07-29T04:00:00Z")
        val end = Instant.parse("2026-07-29T05:30:00Z")
        val now = Instant.parse("2026-07-29T05:00:00Z")

        fun row(
            nid: String? = "1",
            marked: Boolean = false,
            markable: Boolean = false,
        ) = Session("Maths", "Section B", 2, start, end, nid, null, null, null, marked = marked, markable = markable)

        assertEquals(SessionState.MARKED, sessionState(row(marked = true, markable = true), now))
        assertEquals(SessionState.OPEN, sessionState(row(markable = true), now))
        assertEquals(SessionState.UPCOMING, sessionState(row(), start.minusSeconds(1)))
        assertEquals(SessionState.MISSED, sessionState(row(), now))
        assertEquals(SessionState.NO_ATTENDANCE, sessionState(row(nid = null), now))
        assertEquals(SessionState.DONE, sessionState(row(nid = null), end.plusMillis(1)))
        // A schedule-only session is done only after its end instant.
        assertEquals(SessionState.NO_ATTENDANCE, sessionState(row(nid = null), end))
    }

    @Test
    fun formatsDatesInIst() {
        val instant = LocalDateTime.of(2026, 8, 8, 0, 15).atZone(LMS_ZONE).toInstant()
        assertEquals("2026-08-08", ymd(instant))
        assertEquals("2026-08-09", ymd(addDays(LocalDateTime.of(2026, 8, 8, 0, 0).toLocalDate(), 1)))
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), LMS_ZONE.rules.getOffset(instant))
    }

    private fun event(
        nid: String,
        title: String,
        url: String,
        start: String = "2026-07-29 09:30:00",
        end: String? = "2026-07-29 11:00:00",
        trainers: String? = null,
        subject: String? = null,
    ) = CalendarEvent(nid, title, url, start, end, trainers = trainers, subject = subject)
}
