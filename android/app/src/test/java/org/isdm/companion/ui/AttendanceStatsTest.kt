package org.isdm.companion.ui

import java.math.BigDecimal
import org.isdm.companion.engine.AttendanceSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class AttendanceStatsTest {
    @Test
    fun `profile exposes every LMS attendance classification as a statistic`() {
        val summary = AttendanceSummary(
            total = 57,
            present = 40,
            absent = 14,
            notMarked = 3,
            presentPercentage = BigDecimal("70.18"),
        )

        assertEquals(
            listOf(
                AttendanceStatValue("Present", "40"),
                AttendanceStatValue("Absent", "14"),
                AttendanceStatValue("Not marked", "3"),
                AttendanceStatValue("Total", "57"),
            ),
            attendanceStatValues(summary),
        )
    }
}
