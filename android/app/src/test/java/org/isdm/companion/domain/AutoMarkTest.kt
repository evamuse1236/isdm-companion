package org.isdm.companion.domain

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoMarkTest {
    @Test
    fun startsOffAndArmingReturnsAnActiveStatus() {
        val clock = MutableClock(instant("2026-08-08T09:00:00+05:30"))
        val policy = AutoMarkPolicy(windowMs = 2 * HOUR_MS, clock = clock)

        assertEquals(AutoMarkReason.OFF, policy.status().reason)
        assertFalse(policy.status().armed)

        val armed = policy.arm()
        assertTrue(armed.active)
        assertTrue(armed.armed)
        assertFalse(armed.expired)
        assertEquals(AutoMarkReason.ACTIVE, armed.reason)
        assertEquals(2 * HOUR_MS, armed.remainingMs)
        assertEquals(2.0, armed.windowHours, 0.0)
        assertEquals(instant("2026-08-08T11:00:00+05:30"), armed.expiresAt)
    }

    @Test
    fun expiryIsInactiveAtTheExactWindowEndAndCanBeRearmed() {
        val start = instant("2026-08-08T09:00:00+05:30")
        val clock = MutableClock(start)
        val policy = AutoMarkPolicy(windowMs = HOUR_MS, clock = clock)
        policy.arm()

        clock.current = start.plusMillis(HOUR_MS)
        val expired = policy.status()
        assertFalse(expired.active)
        assertTrue(expired.armed)
        assertTrue(expired.expired)
        assertEquals(AutoMarkReason.WINDOW_ELAPSED, expired.reason)
        assertNull(expired.remainingMs)

        val rearmed = policy.arm()
        assertTrue(rearmed.active)
        assertEquals(AutoMarkReason.ACTIVE, rearmed.reason)
    }

    @Test
    fun armingCannotCrossAnIstCalendarDay() {
        val start = instant("2026-08-08T23:59:00+05:30")
        val clock = MutableClock(start)
        val policy = AutoMarkPolicy(clock = clock)
        policy.arm()

        clock.current = instant("2026-08-09T00:00:00+05:30")
        val status = policy.status()
        assertFalse(status.active)
        assertTrue(status.expired)
        assertEquals(AutoMarkReason.NEW_DAY, status.reason)
    }

    @Test
    fun nonPositiveWindowMeansNoExpiry() {
        val clock = MutableClock(instant("2026-08-08T09:00:00+05:30"))
        val policy = AutoMarkPolicy(windowMs = -1, clock = clock)
        val status = policy.arm()

        assertTrue(status.active)
        assertEquals(0.0, status.windowHours, 0.0)
        assertNull(status.expiresAt)
        assertNull(status.remainingMs)
    }

    @Test
    fun disarmClearsTheArmingAndStatus() {
        val policy = AutoMarkPolicy(
            enabled = true,
            clock = MutableClock(instant("2026-08-08T09:00:00+05:30")),
        )
        assertTrue(policy.isActive())

        val status = policy.disarm()
        assertFalse(status.active)
        assertFalse(status.armed)
        assertFalse(status.expired)
        assertEquals(AutoMarkReason.OFF, status.reason)
    }

    @Test
    fun computesNextTimeOfDayAndRollsEqualityToTomorrow() {
        val now = instant("2026-08-08T10:00:30+05:30")

        assertEquals(30_000L, msUntilTimeOfDay("10:01", now))
        assertEquals(Duration.ofHours(23).plusMinutes(59).plusSeconds(30).toMillis(), msUntilTimeOfDay("10:00", now))
        assertEquals(Duration.ofDays(1).toMillis(), msUntilTimeOfDay("10:01", instant("2026-08-08T10:01:00+05:30")))
    }

    @Test
    fun rejectsMalformedTimeOfDay() {
        val now = instant("2026-08-08T10:00:00+05:30")

        assertNull(msUntilTimeOfDay(null, now))
        assertNull(msUntilTimeOfDay("", now))
        assertNull(msUntilTimeOfDay("24:00", now))
        assertNull(msUntilTimeOfDay("10:60", now))
        assertNull(msUntilTimeOfDay("10:0", now))
    }

    private fun instant(value: String): Instant = Instant.parse(
        java.time.OffsetDateTime.parse(value).toInstant().toString(),
    )

    private class MutableClock(
        var current: Instant,
        private val zone: ZoneId = LMS_ZONE,
    ) : Clock() {
        override fun instant(): Instant = current

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
