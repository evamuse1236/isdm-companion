package org.isdm.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCountdownTest {
    @Test
    fun `keeps sub-hour countdowns in minutes`() {
        assertEquals("1 minute", formatSessionCountdown(1))
        assertEquals("48 minutes", formatSessionCountdown(48))
        assertEquals("60 minutes", formatSessionCountdown(60))
    }

    @Test
    fun `formats longer countdowns as hours and minutes`() {
        assertEquals("1h 1m", formatSessionCountdown(61))
        assertEquals("2h", formatSessionCountdown(120))
        assertEquals("11h 36m", formatSessionCountdown(696))
    }
}
