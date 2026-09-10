package org.isdm.companion.ui

import java.time.Instant
import org.isdm.companion.domain.SessionState
import org.isdm.companion.engine.CompanionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleClockTest {
    private val now = Instant.parse("2026-09-05T05:00:00Z")

    @Test
    fun `empty and completed days do not keep a clock running`() {
        assertNull(scheduleClockDelayMillis(emptyList(), now))
        assertNull(scheduleClockDelayMillis(listOf(session(now.minusSeconds(3600), now)), now))
    }

    @Test
    fun `live sessions keep their second-level progress clock`() {
        assertEquals(1_000L, scheduleClockDelayMillis(listOf(session(now, now.plusSeconds(3600))), now))
    }

    @Test
    fun `upcoming countdown wakes on a minute boundary or the exact class start`() {
        val future = session(now.plusSeconds(3600), now.plusSeconds(7200))
        assertEquals(1_000L, scheduleClockDelayMillis(listOf(future), now))
        assertEquals(36_000L, scheduleClockDelayMillis(listOf(future), now.plusSeconds(25)))
        val startsSoon = session(now.plusSeconds(7), now.plusSeconds(3600))
        assertEquals(7_000L, scheduleClockDelayMillis(listOf(startsSoon), now))
        assertEquals(1_000L, scheduleClockDelayMillis(listOf(startsSoon), startsSoon.start))
    }

    @Test
    fun `a future-day screen schedules at most sixty-one updates in an hour`() {
        val future = session(now.plusSeconds(86_400), now.plusSeconds(90_000))
        var clock = now
        var updates = 0
        while (clock < now.plusSeconds(3600)) {
            clock = clock.plusMillis(scheduleClockDelayMillis(listOf(future), clock)!!)
            updates++
        }
        assertEquals(61, updates)
    }

    private fun session(start: Instant, end: Instant) = CompanionSession(
        nid = null, eventNid = null, name = "Class", cohort = null,
        sessionNumber = null, start = start, end = end, state = SessionState.UPCOMING,
    )
}
