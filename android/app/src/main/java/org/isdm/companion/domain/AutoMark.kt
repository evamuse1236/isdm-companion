package org.isdm.companion.domain

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private const val HOUR_MS = 3_600_000L
private val TIME_OF_DAY_RE = Regex("^(\\d{1,2}):(\\d{2})$")

/**
 * Pure auto-mark guard.  It deliberately does not persist its arming: a process restart must
 * not silently begin marking a new class day.  The policy is clock-injected for deterministic
 * tests and always compares calendar days in [LMS_ZONE].
 */
class AutoMarkPolicy(
    enabled: Boolean = false,
    windowMs: Long = 0L,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = LMS_ZONE,
) {
    private val windowMs: Long = windowMs.coerceAtLeast(0L)
    private var armedAt: Instant? = if (enabled) clock.instant() else null

    fun arm(): AutoMarkStatus {
        armedAt = clock.instant()
        return status()
    }

    fun disarm(): AutoMarkStatus {
        armedAt = null
        return status()
    }

    val expiresAt: Instant?
        get() = armedAt?.let { armed ->
            if (windowMs == 0L) null else armed.plusMillis(windowMs)
        }

    fun isActive(): Boolean = isActiveAt(clock.instant())

    /** Armed at some point, but no longer active. */
    fun isExpired(): Boolean = armedAt != null && !isActive()

    fun reason(): AutoMarkReason = reasonAt(clock.instant())

    /** Milliseconds remaining, or null when inactive/unbounded. */
    fun remainingMs(): Long? {
        val now = clock.instant()
        return remainingMsAt(now)
    }

    fun status(): AutoMarkStatus {
        val now = clock.instant()
        val armed = armedAt != null
        val active = isActiveAt(now)
        val expires = armedAt?.let { armedAtValue ->
            if (windowMs == 0L) null else armedAtValue.plusMillis(windowMs)
        }
        return AutoMarkStatus(
            active = active,
            expired = armed && !active,
            armed = armed,
            reason = reasonAt(now),
            expiresAt = expires,
            remainingMs = remainingMsAt(now),
            windowHours = windowMs / HOUR_MS.toDouble(),
        )
    }

    private fun isActiveAt(now: Instant): Boolean {
        val armed = armedAt ?: return false
        if (windowMs != 0L && !now.isBefore(armed.plusMillis(windowMs))) return false
        return now.atZone(zone).toLocalDate() == armed.atZone(zone).toLocalDate()
    }

    private fun reasonAt(now: Instant): AutoMarkReason {
        val armed = armedAt ?: return AutoMarkReason.OFF
        if (isActiveAt(now)) return AutoMarkReason.ACTIVE
        if (windowMs != 0L && !now.isBefore(armed.plusMillis(windowMs))) {
            return AutoMarkReason.WINDOW_ELAPSED
        }
        return AutoMarkReason.NEW_DAY
    }

    private fun remainingMsAt(now: Instant): Long? {
        if (!isActiveAt(now) || windowMs == 0L) return null
        return Duration.between(now, armedAt!!.plusMillis(windowMs)).toMillis()
    }
}

/**
 * Milliseconds until the next local occurrence of HH:MM.  Blank or malformed input returns
 * null.  Equality rolls to tomorrow, matching the desktop shutdown helper.
 */
fun msUntilTimeOfDay(
    spec: String?,
    now: Instant = Instant.now(),
    zone: ZoneId = LMS_ZONE,
): Long? {
    val match = TIME_OF_DAY_RE.matchEntire(spec.orEmpty().trim()) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    if (hour > 23 || minute > 59) return null

    val localNow = now.atZone(zone)
    var target = localNow.toLocalDate().atTime(hour, minute).atZone(zone)
    if (!target.toInstant().isAfter(now)) target = target.plusDays(1)
    return Duration.between(now, target.toInstant()).toMillis()
}
