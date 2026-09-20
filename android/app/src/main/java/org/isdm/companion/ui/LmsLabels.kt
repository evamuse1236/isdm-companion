package org.isdm.companion.ui

// Presentation only: source titles, IDs, course matching and persisted data remain intact.
private val cohortPrefix = Regex("(?i)\\bB\\d+\\s*[-_]\\s*T\\d+\\s*[-_]\\s*")
private val courseAliases = listOf(
    Regex("(?i)\\bState[, ]+Market\\s+(?:and|&)\\s+Society\\b") to "SMS",
    Regex("(?i)\\bWhat\\s+is\\s+Development\\b") to "WID",
    Regex("(?i)\\bPersonal\\s+Mastery(?:\\s+and|\\s*&)\\s+Development\\s+of\\s+Leadership\\b") to "PMDL",
)

internal fun displayLmsLabel(source: String): String {
    val cleaned = cohortPrefix.replace(source, "")
    return courseAliases.fold(cleaned) { label, (pattern, shortcut) -> pattern.replace(label, shortcut) }
        .trim().ifEmpty { source }
}
