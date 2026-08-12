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

    @Test
    fun `redacts common header and oauth secret forms`() {
        val raw = "Authorization: Bearer auth-secret Set-Cookie: sid=cookie-secret " +
            "access_token=access-secret refresh_token=refresh-secret client_secret=client-secret"

        val sanitized = sanitizeDiagnosticValue(raw)

        assertFalse(sanitized.contains("auth-secret"))
        assertFalse(sanitized.contains("cookie-secret"))
        assertFalse(sanitized.contains("access-secret"))
        assertFalse(sanitized.contains("refresh-secret"))
        assertFalse(sanitized.contains("client-secret"))
    }
}
