package org.isdm.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompanionControlsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bottomNavigationExposesSelectableTouchTargetsOnNarrowScreens() {
        val destination = mutableStateOf(Destination.SCHEDULE)

        compose.setContent {
            Box(Modifier.width(320.dp)) {
                CompanionNavBar(
                    destination = destination.value,
                    onChange = { destination.value = it },
                    onIssueReport = {},
                )
            }
        }

        compose.onNodeWithText("Schedule")
            .assertIsSelected()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Readings")
            .assertIsNotSelected()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithText("Readings").assertIsSelected()
        compose.onNodeWithText("Profile")
            .assertIsNotSelected()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun automaticAttendanceSettingsControlInvokesItsAction() {
        var settingsOpened = false

        compose.setContent {
            AutomaticAttendanceSettingsButton(onClick = { settingsOpened = true })
        }

        compose.onNodeWithContentDescription("Automatic attendance settings")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle { check(settingsOpened) }
    }
}
