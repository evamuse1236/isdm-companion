package org.isdm.companion.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.time.Instant
import org.junit.Test

class AndroidDiagnosticsLoggerTest {
    @Test
    fun `debug records retain operational evidence and frames without payloads or messages`() {
        val error = IllegalStateException("private student name and unlabelled-password", RuntimeException("raw LMS html"))
        error.stackTrace = arrayOf(StackTraceElement("org.isdm.companion.Example", "refresh", "Example.kt", 42))
        val line = formatDiagnosticRecord(
            Instant.EPOCH, "test-run", 1, "main", "refresh_failed",
            mapOf("duration_ms" to "123", "email" to "student@example.com", "latitude" to "28.629",
                "password" to "secret", "payload" to "LMS html", "session_ids" to "personal-session",
                "reason" to "http https://lms.test/private?token=secret\nforged_event"), error,
        )
        assertTrue(line.contains("duration_ms=123"))
        assertTrue(line.contains("org.isdm.companion.Example.refresh:42"))
        assertTrue(line.contains("java.lang.IllegalStateException"))
        listOf("private student", "unlabelled-password", "raw LMS", "student@example", "28.629", "secret", "personal-session", "\n")
            .forEach { assertFalse(line.contains(it)) }
    }

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
