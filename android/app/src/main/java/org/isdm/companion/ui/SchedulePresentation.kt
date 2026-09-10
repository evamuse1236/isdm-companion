package org.isdm.companion.ui

import java.time.Instant
import java.time.LocalDate
import java.time.Duration
import org.isdm.companion.engine.CompanionSession
import org.isdm.companion.engine.LMS_ZONE

internal enum class ScheduleHighlightKind {
    HAPPENING_NOW,
    UP_NEXT,
}

internal data class ScheduleHighlight(
    val session: CompanionSession,
    val kind: ScheduleHighlightKind,
)

internal fun scheduleClockDelayMillis(sessions: List<CompanionSession>, now: Instant): Long? {
    if (sessions.any { it.start <= now && now < it.end }) return 1_000L
    val nextStart = sessions.filter { it.start > now }.minOfOrNull { it.start } ?: return null
    val untilStart = Duration.between(now, nextStart).toMillis().coerceAtLeast(1L)
    // The displayed countdown floors to minutes; keep its next change within one second.
    return minOf(untilStart, untilStart % 60_000L + 1_000L)
}

internal fun scheduleHighlights(
    sessions: List<CompanionSession>,
    selectedDate: LocalDate,
    today: LocalDate,
    now: Instant,
): List<ScheduleHighlight> {
    if (selectedDate < today) return emptyList()
    val selectedSessions = sessions
        .filter { it.start.atZone(LMS_ZONE).toLocalDate() == selectedDate }
    if (selectedDate != today) {
        return selectedSessions
            .minByOrNull { it.start }
            ?.let { listOf(ScheduleHighlight(it, ScheduleHighlightKind.UP_NEXT)) }
            .orEmpty()
    }
    val active = selectedSessions.filter { it.start <= now && now < it.end }
    if (active.isNotEmpty()) {
        return active.map { ScheduleHighlight(it, ScheduleHighlightKind.HAPPENING_NOW) }
    }
    return selectedSessions
        .filter { it.start > now }
        .minByOrNull { it.start }
        ?.let { listOf(ScheduleHighlight(it, ScheduleHighlightKind.UP_NEXT)) }
        .orEmpty()
}
