package org.isdm.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportTest {
    @Test
    fun `issue report sheet opts into short-screen keyboard-safe layout`() {
        val policy = issueReportSheetLayoutPolicy()

        assertTrue(policy.scrollable)
        assertTrue(policy.imeSafe)
        assertTrue(policy.systemInsetsSafe)
    }

    @Test
    fun `report FAB description names issues and suggestions`() {
        assertEquals("Report issues and suggestions", ISSUE_REPORT_FAB_CONTENT_DESCRIPTION)
    }

    @Test
    fun betaEnrollmentRequiresInviteSectionAndExplicitConsent() {
        assertFalse(isBetaEnrollmentValid("", "Section A", consented = true))
        assertFalse(isBetaEnrollmentValid("BLUE-MANGO", "", consented = true))
        assertFalse(isBetaEnrollmentValid("BLUE-MANGO", "Section A", consented = false))
        assertTrue(isBetaEnrollmentValid("BLUE-MANGO", "Section A", consented = true))
    }

    @Test
    fun directReportAcceptsOnlySupportedCategoriesAndText() {
        assertTrue(isBetaReportValid("issue", "Schedule is missing"))
        assertTrue(isBetaReportValid("suggestion", "Show my PLC"))
        assertFalse(isBetaReportValid("other", "Something"))
        assertFalse(isBetaReportValid("issue", "  "))
    }

    @Test
    fun reportTitleUsesCategoryAndFirstMeaningfulLine() {
        assertEquals(
            "Suggestion: Show PLC sessions separately",
            betaReportTitle("suggestion", "\n Show PLC sessions separately\nMore details"),
        )
    }

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
