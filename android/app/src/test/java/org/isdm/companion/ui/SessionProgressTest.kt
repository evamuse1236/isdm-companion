package org.isdm.companion.ui

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionProgressTest {
    @Test
    fun `clock pads minutes and seconds`() {
        assertEquals("00:09", formatSessionClock(9))
        assertEquals("47:12", formatSessionClock(47 * 60 + 12))
        assertEquals("60:00", formatSessionClock(3_600))
    }

    @Test
    fun `clock clamps negative time to zero`() {
        assertEquals("00:00", formatSessionClock(-5))
    }

    @Test
    fun `progress is zero before the session starts`() {
        val start = Instant.parse("2026-08-21T06:00:00Z")
        val end = start.plusSeconds(3_600)
        assertEquals(0f, sessionProgressFraction(start.minusSeconds(120), start, end))
    }

    @Test
    fun `progress is half way through the session`() {
        val start = Instant.parse("2026-08-21T06:00:00Z")
        val end = start.plusSeconds(3_600)
        assertEquals(0.5f, sessionProgressFraction(start.plusSeconds(1_800), start, end))
    }

    @Test
    fun `progress clamps after the session ends`() {
        val start = Instant.parse("2026-08-21T06:00:00Z")
        val end = start.plusSeconds(3_600)
        assertEquals(1f, sessionProgressFraction(end.plusSeconds(90), start, end))
    }

    @Test
    fun `progress guards against empty or inverted sessions`() {
        val start = Instant.parse("2026-08-21T06:00:00Z")
        assertEquals(0f, sessionProgressFraction(start.plusSeconds(30), start, start))
        assertEquals(0f, sessionProgressFraction(start.plusSeconds(30), start.plusSeconds(600), start))
    }

    @Test
    fun `remaining clock uses the same duration source as the row`() {
        val start = Instant.parse("2026-08-21T06:00:00Z")
        val end = start.plusSeconds(47 * 60 + 12)
        assertEquals("47:12", formatSessionClock(Duration.between(start, end).seconds))
    }
}
