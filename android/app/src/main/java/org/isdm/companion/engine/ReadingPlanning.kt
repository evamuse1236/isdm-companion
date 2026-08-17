package org.isdm.companion.engine

import java.time.Instant

data class ReadingSessionGroup(
    val sessionNumber: Int,
    val readings: List<ReadingItem>,
)

data class CourseReadingPlan(
    val nextSession: ReadingSessionGroup?,
    val upcomingSessions: List<ReadingSessionGroup>,
    val general: List<ReadingItem>,
)

fun defaultReadingCourseId(
    readings: List<ReadingItem>,
    sessions: List<CompanionSession>,
    now: Instant,
): String? = readings
    .groupBy { it.catId }
    .mapNotNull { (courseId, courseReadings) ->
        val courseName = courseReadings.firstOrNull()?.courseName ?: return@mapNotNull null
        val start = sessions.asSequence()
            .filter { it.end >= now && it.matchesCourse(courseName) }
            .minOfOrNull { it.start }
            ?: return@mapNotNull null
        courseId to start
    }
    .minWithOrNull(compareBy<Pair<String, Instant>> { it.second }.thenBy { it.first })
    ?.first

fun planCourseReadings(
    readings: List<ReadingItem>,
    sessions: List<CompanionSession>,
    now: Instant,
): CourseReadingPlan {
    if (readings.isEmpty()) return CourseReadingPlan(null, emptyList(), emptyList())
    val ordered = compareBy<ReadingItem> { it.done }
        .thenByDescending { it.mandatory }
        .thenBy { it.title.lowercase() }
    val numbered = readings.filter { it.sessionNumber != null }
        .groupBy { requireNotNull(it.sessionNumber) }
        .mapValues { (_, values) -> values.sortedWith(ordered) }
    val courseName = readings.first().courseName
    val matchingStarts = sessions.asSequence()
        .filter { it.end >= now && it.sessionNumber != null && it.matchesCourse(courseName) }
        .groupBy { requireNotNull(it.sessionNumber) }
        .mapValues { (_, values) -> values.minOf { it.start } }
    val groups = numbered.entries
        .map { ReadingSessionGroup(it.key, it.value) }
        .sortedWith(compareBy<ReadingSessionGroup> { matchingStarts[it.sessionNumber] ?: Instant.MAX }.thenBy { it.sessionNumber })
    val nextNumber = groups.firstOrNull()?.sessionNumber
    return CourseReadingPlan(
        nextSession = groups.firstOrNull { it.sessionNumber == nextNumber },
        upcomingSessions = groups.filterNot { it.sessionNumber == nextNumber },
        general = readings.filter { it.sessionNumber == null }.sortedWith(ordered),
    )
}

private fun CompanionSession.matchesCourse(courseName: String): Boolean {
    val course = courseName.normalizedWords()
    if (course.length < 4) return false
    return listOfNotNull(subject, name).any { candidate ->
        val normalized = candidate.normalizedWords()
        normalized.contains(course) || course.contains(normalized)
    }
}

private fun String.normalizedWords(): String = lowercase()
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")
