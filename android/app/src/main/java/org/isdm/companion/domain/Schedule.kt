package org.isdm.companion.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import java.util.Locale

/** The LMS sends wall-clock timestamps in IST without an offset. */
val LMS_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

private val LMS_TIME_RE = Regex(
    """^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?""",
)
private val ATTENDANCE_PREFIX_RE = Regex("^\\s*Attendance\\s*-\\s*", RegexOption.IGNORE_CASE)
private val SESSION_SUFFIX_RE = Regex("\\s*-\\s*Session\\s+(\\d+)\\s*$", RegexOption.IGNORE_CASE)
private val COHORT_SUFFIX_RE = Regex(
    "\\s*-\\s*(Section\\s+[A-Z](?:\\s*&\\s*[A-Z])*|Group\\s+\\d+)\\s*$",
    RegexOption.IGNORE_CASE,
)
private val COHORT_RE = Regex(
    "\\b(Section\\s+[A-Z](?:\\s*&\\s*[A-Z])*|Group\\s+\\d+)\\b",
    RegexOption.IGNORE_CASE,
)
private val GROUP_RE = Regex("^Group\\s+(\\d+)$", RegexOption.IGNORE_CASE)
private val SECTION_LETTER_RE = Regex("\\b[A-Z]\\b")

/**
 * Parse the LMS's wall-clock timestamp into an Instant in [LMS_ZONE].
 *
 * The JavaScript implementation accepts a prefix and ignores trailing text.  It also lets
 * Date normalise out-of-range month/day/hour values.  Building from the first day of the year
 * and applying offsets preserves those two useful behaviours while keeping the zone explicit.
 */
fun parseLmsTime(value: String?): Instant? {
    val match = LMS_TIME_RE.find(value.orEmpty()) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toLongOrNull() ?: return null
    val day = match.groupValues[3].toLongOrNull() ?: return null
    val hour = match.groupValues[4].toLongOrNull() ?: return null
    val minute = match.groupValues[5].toLongOrNull() ?: return null
    val second = match.groupValues.getOrNull(6)?.toLongOrNull() ?: 0L

    return try {
        LocalDateTime.of(year, 1, 1, 0, 0)
            .plusMonths(month - 1L)
            .plusDays(day - 1L)
            .plusHours(hour)
            .plusMinutes(minute)
            .plusSeconds(second)
            .atZone(LMS_ZONE)
            .toInstant()
    } catch (_: RuntimeException) {
        null
    }
}

fun ymd(date: LocalDate): String = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

fun ymd(instant: Instant, zone: ZoneId = LMS_ZONE): String = ymd(instant.atZone(zone).toLocalDate())

fun addDays(date: LocalDate, days: Long): LocalDate = date.plusDays(days)

/** Parse "Attendance - Maths - Section B - Session 2" into its display pieces. */
fun parseTitle(rawTitle: String?): ParsedTitle {
    val original = rawTitle.orEmpty()
    var rest = original.replaceFirst(ATTENDANCE_PREFIX_RE, "").trim()
    var session: Int? = null
    var cohort: String? = null

    val sessionMatch = SESSION_SUFFIX_RE.find(rest)
    if (sessionMatch != null) {
        session = sessionMatch.groupValues[1].toIntOrNull()
        rest = rest.substring(0, sessionMatch.range.first).trim()
    }

    val cohortMatch = COHORT_SUFFIX_RE.find(rest)
    if (cohortMatch != null) {
        cohort = cohortMatch.groupValues[1].replace(Regex("\\s+"), " ").trim()
        rest = rest.substring(0, cohortMatch.range.first).trim()
    }

    return ParsedTitle(
        name = rest.ifEmpty { original.trim() },
        cohort = cohort,
        session = session,
    )
}

/** Is this the personalised attendance row rather than the batch-wide event row? */
fun isAttendanceEvent(event: CalendarEvent): Boolean = event.url?.contains("/classroom/") == true

/** Infer section/group membership from the LMS's personalised attendance rows. */
fun detectCohorts(events: Iterable<CalendarEvent>): Cohorts {
    val sections = linkedSetOf<String>()
    val groups = linkedSetOf<String>()

    for (event in events) {
        if (!isAttendanceEvent(event)) continue
        val cohort = parseTitle(event.title).cohort ?: continue
        val group = GROUP_RE.matchEntire(cohort)
        if (group != null) {
            groups += group.groupValues[1]
            continue
        }
        val letters = SECTION_LETTER_RE.findAll(cohort).map { it.value }.toList()
        if (letters.size == 1) sections += letters.single().uppercase(Locale.ROOT)
    }

    return Cohorts(sections = sections, groups = groups)
}

/** Parse the comma-separated COHORTS override used by the desktop setup. */
fun parseCohortOverride(text: String?): Cohorts? {
    val sections = linkedSetOf<String>()
    val groups = linkedSetOf<String>()

    for (token in text.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
        val group = GROUP_RE.matchEntire(token)
        if (group != null) {
            groups += group.groupValues[1]
            continue
        }
        SECTION_LETTER_RE.findAll(token)
            .map { it.value.uppercase(Locale.ROOT) }
            .forEach { sections += it }
    }

    return if (sections.isNotEmpty() || groups.isNotEmpty()) {
        Cohorts(sections = sections, groups = groups)
    } else {
        null
    }
}

/** Does a batch-wide event belong to the caller's section/group? */
private fun belongsToMe(event: CalendarEvent, cohorts: Cohorts): Boolean {
    val token = COHORT_RE.find(event.title)?.groupValues?.getOrNull(1) ?: return true
    val group = GROUP_RE.matchEntire(token)
    if (group != null) return cohorts.groups.isEmpty() || group.groupValues[1] in cohorts.groups

    val letters = SECTION_LETTER_RE.findAll(token).map { it.value.uppercase(Locale.ROOT) }.toList()
    if (letters.isEmpty()) return true
    if (cohorts.sections.isEmpty()) return true
    return letters.any { it in cohorts.sections }
}

private data class BuildingSession(
    val key: String,
    val name: String,
    val cohort: String?,
    val session: Int?,
    val start: Instant,
    val end: Instant?,
    var nid: String? = null,
    var eventNid: String? = null,
    var subject: String? = null,
    var trainer: String? = null,
    var mine: Boolean = true,
)

private fun mergeKey(event: CalendarEvent): String {
    val title = parseTitle(event.title)
    return "${title.name.lowerRoot()}|${title.cohort?.lowerRoot().orEmpty()}|${title.session ?: ""}|${event.start}"
}

/**
 * Merge duplicate batch-wide/attendance entries into one row and filter rows outside the
 * caller's section/group.  The returned order is start time, then name, like the JS client.
 */
fun buildSessions(events: Iterable<CalendarEvent>, cohorts: Cohorts): List<Session> {
    val byKey = LinkedHashMap<String, BuildingSession>()

    for (event in events) {
        val start = parseLmsTime(event.start) ?: continue
        val end = parseLmsTime(event.end)
        val key = mergeKey(event)
        val title = parseTitle(event.title)
        val row = byKey.getOrPut(key) {
            BuildingSession(
                key = key,
                name = title.name,
                cohort = title.cohort,
                session = title.session,
                start = start,
                end = end,
            )
        }

        if (isAttendanceEvent(event)) {
            row.nid = event.nid
            if (!event.subject.isNullOrEmpty()) row.subject = event.subject
            if (!event.trainers.isNullOrEmpty()) row.trainer = event.trainers
        } else {
            row.eventNid = event.nid
            // An attendance row proves ownership regardless of the title.  This is also
            // faithful to the JS implementation when the batch-wide row arrives first.
            row.mine = if (!row.nid.isNullOrEmpty()) true else belongsToMe(event, cohorts)
        }
    }

    return byKey.values
        .asSequence()
        .filter { !it.nid.isNullOrEmpty() || it.mine }
        .map {
            Session(
                name = it.name,
                cohort = it.cohort,
                session = it.session,
                start = it.start,
                end = it.end,
                nid = it.nid,
                eventNid = it.eventNid,
                subject = it.subject,
                trainer = it.trainer,
            )
        }
        .sortedWith(compareBy<Session> { it.startMs }.thenBy { it.name })
        .toList()
}

/** Compute the same six UI states emitted by the desktop service. */
fun sessionState(row: Session, now: Instant): SessionState {
    if (row.nid.isNullOrEmpty()) {
        return if (row.endMs < now.toEpochMilli()) SessionState.DONE else SessionState.NO_ATTENDANCE
    }
    if (row.marked) return SessionState.MARKED
    if (row.markable) return SessionState.OPEN
    if (now.toEpochMilli() < row.startMs) return SessionState.UPCOMING
    return SessionState.MISSED
}

fun sessionState(row: Session, nowMs: Long): SessionState = sessionState(row, Instant.ofEpochMilli(nowMs))
