package org.isdm.companion.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class MonitoringServiceTest {
    @Test
    fun `auto tick fails closed when fresh remote preflight cannot allow it`() = runTest {
        var ticks = 0

        val blocked = runAutoAttendanceTick(
            preflight = { AutoPreflight(false, "remote_stop") },
            tick = { ticks += 1 },
        )
        val offline = runAutoAttendanceTick(
            preflight = { throw java.io.IOException("offline") },
            tick = { ticks += 1 },
        )

        assertFalse(blocked)
        assertFalse(offline)
        assertEquals(0, ticks)
    }

    @Test
    fun `auto tick runs only after fresh remote preflight allows it`() = runTest {
        var ticks = 0

        val ran = runAutoAttendanceTick(
            preflight = { AutoPreflight(true, "allowed") },
            tick = { ticks += 1 },
        )

        assertTrue(ran)
        assertEquals(1, ticks)
    }

    @Test
    fun `monitor wake lock covers the active window but stays bounded`() {
        val now = Instant.parse("2026-08-15T03:30:00Z")

        assertEquals(
            11 * 60_000L,
            monitorWakeLockTimeoutMillis(now, now.plusSeconds(10 * 60L)),
        )
        assertEquals(2 * 60 * 60_000L, monitorWakeLockTimeoutMillis(now, now.plusSeconds(4 * 60 * 60L)))
        assertFalse(shouldRenewMonitorWakeLock(isHeld = true, elapsedMillis = 119_999L, renewAtMillis = 120_000L))
        assertTrue(shouldRenewMonitorWakeLock(isHeld = true, elapsedMillis = 120_000L, renewAtMillis = 120_000L))
    }

    @Test
    fun `expired auto arm window does not start refresh retries`() {
        val now = 1_000_000L

        assertNull(autoArmRefreshDeadlineMillis(now, now - 1L))
        assertEquals(now + 10 * 60_000L, autoArmRefreshDeadlineMillis(now, 0L))
    }

    @Test
    fun `auto arm requests are processed serially`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val handled = mutableListOf<String>()
        val bothHandled = CompletableDeferred<Unit>()
        val processor = AutoArmRequestProcessor(backgroundScope, handle = { request ->
            val current = active.incrementAndGet()
            maximumActive.updateAndGet { maxOf(it, current) }
            if (request.sessionIds == setOf("first")) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            handled += request.sessionIds.single()
            if (handled.size == 2) bothHandled.complete(Unit)
            active.decrementAndGet()
        })

        processor.submit(AutoArmRequest(1, 0L, setOf("first")))
        firstStarted.await()
        processor.submit(AutoArmRequest(2, 0L, setOf("second")))
        releaseFirst.complete(Unit)
        bothHandled.await()

        assertEquals(listOf("first", "second"), handled)
        assertEquals(1, maximumActive.get())
    }

    @Test
    fun `initial attendance refresh retries transient failures before the deadline`() = runTest {
        var now = 0L
        var attempts = 0

        val completed = runRetryableUntil(
            deadlineMillis = 120_000L,
            nowMillis = { now },
            pause = { delay -> now += delay },
        ) {
            attempts += 1
            if (attempts < 3) RetryableOperationResult.RETRY else RetryableOperationResult.SUCCESS
        }

        assertTrue(completed)
        assertEquals(3, attempts)
    }

    @Test
    fun `cancelling auto arm requests interrupts current work and drops queued work`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val handled = mutableListOf<String>()
        val processor = AutoArmRequestProcessor(backgroundScope, handle = { request ->
            handled += request.sessionIds.single()
            if (request.sessionIds == setOf("first")) {
                firstStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    firstCancelled.complete(Unit)
                }
            }
        })

        processor.submit(AutoArmRequest(1, 0L, setOf("first")))
        processor.submit(AutoArmRequest(2, 0L, setOf("second")))
        firstStarted.await()

        processor.cancel()
        firstCancelled.await()

        assertEquals(listOf("first"), handled)
    }
}
