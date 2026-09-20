package org.isdm.companion.ui

import java.time.LocalDate

internal fun scheduleDates(start: LocalDate, endExclusive: LocalDate): List<LocalDate> {
    val dayCount = (endExclusive.toEpochDay() - start.toEpochDay()).coerceAtLeast(0)
    return List(dayCount.toInt()) { offset -> start.plusDays(offset.toLong()) }
}

internal fun boundedScheduleDateDelta(
    selectedDate: LocalDate,
    targetDate: LocalDate,
    rangeStart: LocalDate,
    rangeEndExclusive: LocalDate,
): Int? {
    if (targetDate < rangeStart || targetDate >= rangeEndExclusive || targetDate == selectedDate) {
        return null
    }
    return (targetDate.toEpochDay() - selectedDate.toEpochDay()).toInt()
}

internal fun dateTransitionDirection(from: LocalDate, to: LocalDate): Int = when {
    to > from -> 1
    to < from -> -1
    else -> 0
}

/** Monday-based week, clipped to the range actually fetched from the LMS. */
internal fun scheduleWeekDates(selected: LocalDate, start: LocalDate, endExclusive: LocalDate): List<LocalDate> {
    val monday = selected.minusDays((selected.dayOfWeek.value - 1).toLong())
    return (0L..6L).map(monday::plusDays).filter { it >= start && it < endExclusive }
}
