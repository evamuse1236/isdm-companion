package org.isdm.companion.ui

import java.time.Instant
import java.time.LocalDate
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

internal fun scheduleHighlights(
    sessions: List<CompanionSession>,
    selectedDate: LocalDate,
    today: LocalDate,
    now: Instant,
): List<ScheduleHighlight> {
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
