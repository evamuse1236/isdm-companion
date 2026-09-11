package org.isdm.companion.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class AutomaticAttendancePermissionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsKeepsEveryRequiredPermissionInTheGuidedSetup() {
        compose.runOnIdle {
            compose.activity.openAutomaticAttendanceSettings()
        }

        compose.waitUntil(3_000) {
            compose.onAllNodesWithText("Automatic attendance setup").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Precise location").assertIsDisplayed()
        compose.onNodeWithText("Notifications").assertIsDisplayed()
        compose.onNodeWithText("Location all the time").assertIsDisplayed()
        compose.onNodeWithText("Precise alarms").assertIsDisplayed()
        compose.onNodeWithText("Not now").assertIsDisplayed()
    }
}
