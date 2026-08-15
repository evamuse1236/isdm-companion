package org.isdm.companion.ui

import android.content.ComponentName
import android.os.Build
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun launchCreatesUsableContentWithoutClearingSavedLogin() {
        activity.scenario.onActivity { launched ->
            val content = launched.findViewById<ViewGroup>(android.R.id.content)
            assertFalse(launched.isFinishing)
            assertTrue(content.childCount > 0)
        }
    }

    @Test
    fun attendanceIntentTargetIsNotExported() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.packageManager.getActivityInfo(
                ComponentName(app, MainActivity::class.java),
                android.content.pm.PackageManager.ComponentInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        }
        assertFalse(info.exported)
    }

    @Test
    fun pendingPermissionActionsSurviveActivityRecreation() {
        val state = android.os.Bundle()
        val expected = PendingActivityActions(
            markSessionId = "session-42",
            markFromIntent = true,
            autoAttendance = true,
        )

        writePendingActivityActions(state, expected)

        assertEquals(expected, readPendingActivityActions(state))
    }
}
