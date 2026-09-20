package org.isdm.companion.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleWeekTest {
    @Test fun weekCrossesYearBoundaryAndClipsUnavailableDays() {
        val selected = LocalDate.parse("2027-01-01")
        assertEquals(listOf("2026-12-30", "2026-12-31", "2027-01-01", "2027-01-02"),
            scheduleWeekDates(selected, LocalDate.parse("2026-12-30"), LocalDate.parse("2027-01-03")).map { it.toString() })
        assertEquals(7, scheduleWeekDates(selected, selected.minusDays(10), selected.plusDays(10)).size)
        assertEquals(emptyList<LocalDate>(), scheduleWeekDates(selected, selected, selected))
    }
}
