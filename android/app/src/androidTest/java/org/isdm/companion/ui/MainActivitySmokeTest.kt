package org.isdm.companion.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun freshInstallShowsLocalLmsLoginWithoutMakingARequest() {
        compose.onNodeWithText("Sign in to the LMS").assertIsDisplayed()
        compose.onNodeWithText("LMS email").assertIsDisplayed()
        compose.onNodeWithText("LMS password").assertIsDisplayed()
        compose.onNodeWithText("Sign in").assertIsDisplayed()
    }
}
