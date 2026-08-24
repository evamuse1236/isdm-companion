package org.isdm.companion.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleDateNavigationTest {
    private val start = LocalDate.of(2026, 8, 24)
    private val endExclusive = start.plusDays(14)

    @Test
    fun `schedule exposes the full range without recentering around selection`() {
        assertEquals((0L..13L).map(start::plusDays), scheduleDates(start, endExclusive))
    }

    @Test
    fun `date tap returns only bounded movement`() {
        assertEquals(1, boundedScheduleDateDelta(start, start.plusDays(1), start, endExclusive))
        assertEquals(-3, boundedScheduleDateDelta(start.plusDays(3), start, start, endExclusive))
        assertNull(boundedScheduleDateDelta(start, start, start, endExclusive))
        assertNull(boundedScheduleDateDelta(start, start.minusDays(1), start, endExclusive))
        assertNull(boundedScheduleDateDelta(start, endExclusive, start, endExclusive))
    }

    @Test
    fun `date content direction follows chronological movement`() {
        assertEquals(1, dateTransitionDirection(start, start.plusDays(1)))
        assertEquals(-1, dateTransitionDirection(start.plusDays(1), start))
        assertEquals(0, dateTransitionDirection(start, start))
    }
}
