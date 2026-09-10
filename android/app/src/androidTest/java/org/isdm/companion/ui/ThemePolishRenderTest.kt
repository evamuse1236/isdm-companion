package org.isdm.companion.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.Density
import java.io.File
import java.time.Instant
import org.isdm.companion.engine.CompanionSession
import org.isdm.companion.engine.CompanionState
import org.isdm.companion.engine.Identity
import org.isdm.companion.engine.LMS_ZONE
import org.isdm.companion.engine.SyncStatus
import org.junit.Rule
import org.junit.Test

/** Synthetic render fixture uses the actual app composables; no fake learner data ships. */
class ThemePolishRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun incumbentThemeAtNormalAndLargeText() {
        val now = Instant.now()
        val today = now.atZone(LMS_ZONE).toLocalDate()
        val sessions = (0..2).map { index -> CompanionSession(
            nid = "test-$index", eventNid = "test-$index",
            name = listOf("Understanding Social Change", "Research Methods", "Leadership and Learning")[index],
            cohort = "Section A", sessionNumber = index + 1,
            start = now.plusSeconds(1200 + index * 5400L), end = now.plusSeconds(4800 + index * 5400L),
            room = "Classroom 201", trainer = "Course faculty",
        ) }
        val state = CompanionState(now, today, credentialsConfigured = true,
            identity = Identity("fixture", "Learner"), sessions = sessions, scheduleSessions = sessions,
            sync = SyncStatus(lastSuccess = now), scheduleSync = SyncStatus(lastSuccess = now))
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale.value)) {
                MaterialTheme(colorScheme = companionColors(), typography = companionTypography) {
                    Column(Modifier.fillMaxSize().background(Color(0xFFF4F8F7))) {
                        CompanionHeader(state, false, {}, {}, {})
                        DestinationTabs(Destination.SCHEDULE, {})
                        ScheduleContent(state, today, {}, {}, {}, {}, true, { _, _ -> },
                            BetaSetupStatus(true, true), false, {}, {}, emptySet())
                    }
                }
            }
        }
        compose.onNodeWithText("Schedule").assertIsDisplayed()
        capture("schedule-normal")
        compose.runOnIdle { fontScale.value = 1.3f }
        compose.onNodeWithText("Schedule").assertIsDisplayed()
        capture("schedule-large-text")
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(compose.activity.getExternalFilesDir(null), "visual-review").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
