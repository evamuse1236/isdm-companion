package org.isdm.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportTest {
    @Test
    fun issueDescriptionMustContainNonWhitespaceText() {
        assertFalse(hasIssueDescription("   \n\t"))
        assertTrue(hasIssueDescription("Schedule does not refresh"))
    }

    @Test
    fun shareTextTrimsOnlyTheDescriptionEdges() {
        assertEquals(
            "Issue report\n\nSchedule does not refresh",
            issueReportShareText("  Schedule does not refresh  "),
        )
    }
}
