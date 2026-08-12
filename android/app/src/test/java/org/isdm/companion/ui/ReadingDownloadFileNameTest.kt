package org.isdm.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingDownloadFileNameTest {
    @Test
    fun `creates a safe pdf filename from the LMS title`() {
        assertEquals(
            "Session 1 Government Chapter 12.pdf",
            readingDownloadFileName(" Session 1: Government / Chapter 12 "),
        )
    }

    @Test
    fun `uses a useful fallback for punctuation-only titles`() {
        assertEquals("ISDM reading.pdf", readingDownloadFileName("///"))
    }

    @Test
    fun `adds a suffix so repeated downloads do not collide`() {
        assertEquals("Government-20260809-212500.pdf", readingDownloadFileName("Government", "20260809-212500"))
    }
}
