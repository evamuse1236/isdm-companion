package org.isdm.companion.ui

import java.time.LocalDate
import org.isdm.companion.engine.AssessmentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssessmentPresentationTest {
    @Test
    fun `schedule promotes only the next actionable assessment`() {
        val locallyDone = assessment("1", "Reflection", "Not Submitted", done = true)
        val lmsSubmitted = assessment("2", "Memo", "Submitted")
        val actionable = assessment("3", "Group contract", "Not Submitted")

        assertEquals(actionable, nextScheduleAssessment(listOf(locallyDone, lmsSubmitted, actionable)))
        assertEquals(1, actionableAssessmentCount(listOf(locallyDone, lmsSubmitted, actionable)))
        assertTrue(locallyDone.isAssessmentComplete())
        assertTrue(lmsSubmitted.isAssessmentComplete())
        assertFalse(actionable.isAssessmentComplete())
    }

    @Test
    fun `assessment summary distinguishes local done from LMS submitted`() {
        val locallyDone = assessment("1", "Reflection", "Not Submitted", done = true)
        val lmsSubmitted = assessment("2", "Memo", "Submitted")
        val actionable = assessment("3", "Group contract", "Not Submitted")

        assertEquals("All open work marked done", assessmentSummaryLabel(listOf(locallyDone)))
        assertEquals("All listed work submitted", assessmentSummaryLabel(listOf(lmsSubmitted)))
        assertEquals("All open work marked done", assessmentSummaryLabel(listOf(locallyDone, lmsSubmitted)))
        assertEquals("1 assessment open", assessmentSummaryLabel(listOf(locallyDone, actionable)))
        assertEquals("2 assessments open", assessmentSummaryLabel(listOf(actionable, actionable.copy(id = "4"))))
    }

    private fun assessment(id: String, title: String, status: String, done: Boolean = false) =
        AssessmentItem(
            id = id,
            title = title,
            status = status,
            dueDate = LocalDate.of(2026, 9, 20),
            endDate = null,
            submissionUrl = "https://lms/$id",
            done = done,
        )
}
