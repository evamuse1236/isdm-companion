package org.isdm.companion.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NightlyEnrollmentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun enrollsWithNameAndConsentWithoutAnInvite() {
        var submitted = 0
        compose.setContent {
            CompanionTheme(AccentChoice.LAVENDER) {
                BetaEnrollmentContent(false, "Fixture learner", setOf("A"), emptySet(),
                    { name, section, _, _, _, consent ->
                        assertEquals("Fixture learner", name)
                        assertEquals("Section A", section)
                        assertEquals(true, consent)
                        submitted++
                    })
            }
        }
        compose.onNodeWithText("Invite code").assertDoesNotExist()
        val directory = File(compose.activity.getExternalFilesDir(null), "visual-review").apply { mkdirs() }
        File(directory, "nightly-enrollment.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Join nightly").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, submitted) }
        compose.onNodeWithText("I agree to this support roster and installation access control.")
            .performScrollTo().performClick()
        compose.onNodeWithText("Join nightly").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, submitted) }
    }
}
