package org.isdm.companion.platform

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.isdm.companion.engine.CompanionAccessException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BetaAccessControllerTest {
    private var time = Instant.parse("2026-09-11T00:00:00Z")
    private val installation = BetaInstallation("T-03", "test-installation", "test-token")
    private var stored: BetaAccessSnapshot? = null
    private val store = object : BetaAccessStore {
        override fun load() = stored
        override fun save(snapshot: BetaAccessSnapshot) { stored = snapshot }
    }

    @Test fun `suspension persists through restart and offline failures until server restores`() = runBlocking {
        var answer = BetaAccessSnapshot(BetaAccessStatus.SUSPENDED)
        var offline = false
        fun controller() = BetaAccessController({ installation }, {
            if (offline) throw IOException("offline") else answer
        }, store, { time })
        controller().refresh()
        offline = true
        val restarted = controller()
        assertEquals(BetaAccessStatus.SUSPENDED, restarted.refresh().status)
        assertDenied("suspended") { restarted.requireAccess(false, false) }
        offline = false
        answer = BetaAccessSnapshot(BetaAccessStatus.ALLOWED, validUntil = time.plusSeconds(21_600))
        assertEquals(BetaAccessStatus.ALLOWED, restarted.refresh().status)
        restarted.requireAccess(true, false)
    }

    @Test fun `offline lease permits browsing but never immediate attendance verification`() = runBlocking {
        stored = BetaAccessSnapshot(BetaAccessStatus.ALLOWED, time, time.plusSeconds(21_600))
        val controller = BetaAccessController({ installation }, { throw IOException("offline") }, store, { time })
        controller.requireAccess(false, false)
        assertDenied("needs_connection") { controller.requireAccess(true, false) }
        time = time.plusSeconds(21_600)
        assertDenied("needs_connection") { controller.requireAccess(false, false) }
    }

    @Test fun `many failed reads coalesce while cached access remains valid`() = runBlocking {
        stored = BetaAccessSnapshot(BetaAccessStatus.ALLOWED, time.minusSeconds(120), time.plusSeconds(600))
        var calls = 0
        val controller = BetaAccessController({ installation }, { calls++; throw IOException("offline") }, store, { time })
        repeat(12) { controller.requireAccess(false, false) }
        assertEquals(1, calls)
    }

    @Test fun `invalid installation cannot use a cached lease and enrollment cannot mark`() = runBlocking {
        stored = BetaAccessSnapshot(BetaAccessStatus.ALLOWED, time.minusSeconds(120), time.plusSeconds(600))
        val invalid = BetaAccessController({ installation }, { throw BetaApiException(401, "installation_auth_invalid") }, store, { time })
        assertDenied("invalid_installation") { invalid.requireAccess(true, false) }
        val fresh = BetaAccessController({ null }, { error("must not fetch") }, store, { time })
        fresh.requireAccess(false, true)
        assertDenied("not_enrolled") { fresh.requireAccess(true, false) }
    }

    @Test fun `clock rollback and oversized leases cannot extend offline access`() = runBlocking {
        val controller = BetaAccessController({ installation }, {
            BetaAccessSnapshot(BetaAccessStatus.ALLOWED, validUntil = time.plusSeconds(100_000))
        }, store, { time })
        controller.refresh()
        assertEquals(time.plusSeconds(21_600), stored?.validUntil)
        time = time.minusSeconds(1)
        assertEquals(BetaAccessStatus.NEEDS_CONNECTION, stored?.at(time)?.status)
    }

    private suspend fun assertDenied(reason: String, block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue(failure is CompanionAccessException)
        assertEquals(reason, (failure as CompanionAccessException).reason)
    }
}
