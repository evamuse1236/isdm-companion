package org.isdm.companion.domain

import java.time.Instant
import java.util.Locale

/**
 * One entry returned by the LMS calendar endpoint.
 *
 * The calendar contains both batch-wide event entries and personalised attendance entries.
 * An attendance entry is identified by a URL containing `/classroom/` and carries the nid
 * that can be marked.  Optional fields mirror the fields that the current Node client reads.
 */
data class CalendarEvent(
    val nid: String?,
    val title: String,
    val url: String?,
    val start: String,
    val end: String?,
    val className: String? = null,
    val trainers: String? = null,
    val subject: String? = null,
)

data class ParsedTitle(
    val name: String,
    val cohort: String?,
    val session: Int?,
) {
    /** Kotlin-friendly alias for callers that use the longer field name. */
    val sessionNumber: Int?
        get() = session
}

data class Cohorts(
    val sections: Set<String> = emptySet(),
    val groups: Set<String> = emptySet(),
)

enum class SessionState(val wireName: String) {
    MARKED("marked"),
    OPEN("open"),
    UPCOMING("upcoming"),
    MISSED("missed"),
    DONE("done"),
    NO_ATTENDANCE("noattendance"),
}

/** A merged calendar row with epoch-millisecond accessors for stable sorting and comparisons. */
data class Session(
    val name: String,
    val cohort: String?,
    val session: Int?,
    val start: Instant,
    val end: Instant?,
    val nid: String?,
    val eventNid: String?,
    val subject: String?,
    val trainer: String?,
    val room: String? = null,
    val marked: Boolean = false,
    val markable: Boolean = false,
    val status: String? = null,
    val floor: Double? = null,
    val floorLabel: String? = null,
    val detailError: String? = null,
    val endEstimated: Boolean = false,
) {
    val sessionNumber: Int?
        get() = session

    val startMs: Long
        get() = start.toEpochMilli()

    val endMs: Long
        get() = (end ?: start).toEpochMilli()
}

enum class AutoMarkReason(val wireName: String) {
    OFF("off"),
    ACTIVE("active"),
    WINDOW_ELAPSED("window-elapsed"),
    NEW_DAY("new-day"),
}

data class AutoMarkStatus(
    val active: Boolean,
    val expired: Boolean,
    val armed: Boolean,
    val reason: AutoMarkReason,
    val expiresAt: Instant?,
    val remainingMs: Long?,
    val windowHours: Double,
) {
    /** Compatibility alias used by status consumers. */
    val msRemaining: Long?
        get() = remainingMs
}

/** Stable lower-casing used for merge keys and room lookup, independent of device locale. */
internal fun String.lowerRoot(): String = lowercase(Locale.ROOT)
