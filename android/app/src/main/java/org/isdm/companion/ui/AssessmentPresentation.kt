package org.isdm.companion.ui

import org.isdm.companion.engine.AssessmentItem
import org.isdm.companion.engine.isLmsSubmitted

internal fun AssessmentItem.isAssessmentComplete(): Boolean = done || isLmsSubmitted()

internal fun nextScheduleAssessment(assessments: List<AssessmentItem>): AssessmentItem? =
    assessments.firstOrNull { !it.isAssessmentComplete() }

internal fun actionableAssessmentCount(assessments: List<AssessmentItem>): Int =
    assessments.count { !it.isAssessmentComplete() }

internal fun assessmentSummaryLabel(assessments: List<AssessmentItem>): String {
    val openCount = actionableAssessmentCount(assessments)
    return when {
        openCount > 0 -> "$openCount ${if (openCount == 1) "assessment" else "assessments"} open"
        assessments.all { it.isLmsSubmitted() } -> "All listed work submitted"
        else -> "All open work marked done"
    }
}
