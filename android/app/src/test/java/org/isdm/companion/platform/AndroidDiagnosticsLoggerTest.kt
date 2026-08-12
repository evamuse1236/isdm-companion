package org.isdm.companion.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AndroidDiagnosticsLoggerTest {
    @Test
    fun `redacts credentials identifiers and signed urls`() {
        val raw = "student@example.com password=secret cookie=session123 https://lms.test/file?signature=abc"

        val sanitized = sanitizeDiagnosticValue(raw)

        assertEquals("[email] password=[redacted] cookie=[redacted] [url]", sanitized)
        assertFalse(sanitized.contains("secret"))
        assertFalse(sanitized.contains("session123"))
        assertFalse(sanitized.contains("signature=abc"))
    }
}
