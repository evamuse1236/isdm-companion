package org.isdm.companion

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.isdm.companion.engine.CachedSchedule
import org.isdm.companion.engine.CompanionSession
import org.isdm.companion.platform.StoredCredentials
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoAttendanceStartupRestoreContractTest {
    @Test
    fun cachedUpcomingWindowIsRestoredWithoutStartingBackgroundSync() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as CompanionApplication
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val originalEnabled = app.autoAttendanceStore.isEnabled()
        val account = "startup-contract@example.com"
        val now = Instant.now()
        val zone = ZoneId.of("Asia/Kolkata")
        val today = now.atZone(zone).toLocalDate()
        val sessionStart = today.plusDays(1).atTime(LocalTime.of(9, 0)).atZone(zone).toInstant()

        app.autoAttendanceStore.setEnabled(true)
        app.localStore.selectAccount(account)
        app.localStore.saveSchedule(
            CachedSchedule(
                start = today,
                endExclusive = today.plusDays(2),
                sessions = listOf(
                    CompanionSession(
                        nid = "startup-window",
                        eventNid = null,
                        name = "Startup recovery",
                        cohort = null,
                        sessionNumber = null,
                        start = sessionStart,
                        end = sessionStart.plusSeconds(3_600),
                    ),
                ),
                syncedAt = now,
            ),
        )
        app.autoAttendanceScheduler.cancel()

        try {
            restoreAutoAttendanceOnProcessStart(
                enabled = true,
                setupReady = true,
                credentials = StoredCredentials(account, "unused-test-password"),
                selectAccount = app.localStore::selectAccount,
                loadSchedule = app.localStore::loadSchedule,
                schedule = { app.autoAttendanceScheduler.schedule(it.sessions, Instant.now()) },
            )

            assertTrue(app.autoAttendanceStore.scheduledIds().isNotEmpty())
        } finally {
            app.autoAttendanceScheduler.cancel()
            app.autoAttendanceStore.setEnabled(originalEnabled)
        }
    }
}
