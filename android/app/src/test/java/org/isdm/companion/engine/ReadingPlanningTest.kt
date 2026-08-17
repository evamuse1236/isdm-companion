package org.isdm.companion.engine

import org.isdm.companion.domain.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ReadingPlanningTest {
    private val now = Instant.parse("2026-08-09T12:00:00Z")

    @Test
    fun `uses the nearest matching scheduled session and keeps ambiguous readings general`() {
        val readings = listOf(reading("s1", 1), reading("s2", 2), reading("general", null))
        val sessions = listOf(
            session("Another course - Session 1", 1, "2026-08-10T03:30:00Z"),
            session("Attendance - State Market and Society - Session 2", 2, "2026-08-10T06:00:00Z"),
            session("Attendance - State Market and Society - Session 1", 1, "2026-08-11T03:30:00Z"),
        )

        val plan = planCourseReadings(readings, sessions, now)

        assertEquals(2, plan.nextSession?.sessionNumber)
        assertEquals(listOf("s2"), plan.nextSession?.readings?.map { it.vid })
        assertEquals(listOf(1), plan.upcomingSessions.map { it.sessionNumber })
        assertEquals(listOf("general"), plan.general.map { it.vid })
    }

    @Test
    fun `falls back to the lowest explicit session when the schedule has no course match`() {
        val plan = planCourseReadings(
            readings = listOf(reading("s4", 4), reading("s1", 1)),
            sessions = emptyList(),
            now = now,
        )

        assertEquals(1, plan.nextSession?.sessionNumber)
        assertEquals(listOf(4), plan.upcomingSessions.map { it.sessionNumber })
    }

    @Test
    fun `default course follows the nearest matching active or future session`() {
        val readings = listOf(
            reading("later-reading", 1, catId = "later", courseName = "Later Course"),
            reading("next-reading", 1, catId = "next", courseName = "Next Course"),
        )
        val sessions = listOf(
            session("Later Course - Session 1", 1, "2026-08-11T03:30:00Z"),
            session("Next Course - Session 1", 1, "2026-08-10T03:30:00Z"),
        )

        assertEquals("next", defaultReadingCourseId(readings, sessions, now))
    }

    @Test
    fun `course cards are ordered by their nearest active or future session`() {
        val readings = listOf(
            reading("general", null, catId = "general", courseName = "General Course"),
            reading("later", 1, catId = "later", courseName = "Later Course"),
            reading("next", 1, catId = "next", courseName = "Next Course"),
        )
        val sessions = listOf(
            session("Later Course - Session 1", 1, "2026-08-11T03:30:00Z"),
            session("Next Course - Session 1", 1, "2026-08-10T03:30:00Z"),
        )

        assertEquals(
            listOf("next", "later", "general"),
            orderedReadingCourseIds(readings, sessions, now),
        )
    }

    private fun reading(
        id: String,
        sessionNumber: Int?,
        catId: String = "12",
        courseName: String = "State, Market and Society",
    ) = ReadingItem(
        vid = id,
        sid = "91",
        cid = "7",
        catId = catId,
        title = id,
        courseName = courseName,
        sectionName = "Course Readings",
        sourceUrl = "https://lms.isdm.org.in/subtopic/view?vid=$id",
        sessionNumber = sessionNumber,
    )

    private fun session(name: String, number: Int, start: String): CompanionSession {
        val instant = Instant.parse(start)
        return CompanionSession(
            nid = null,
            eventNid = null,
            name = name,
            cohort = null,
            sessionNumber = number,
            start = instant,
            end = instant.plusSeconds(3600),
            state = SessionState.UPCOMING,
        )
    }
}
