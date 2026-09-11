package org.isdm.companion.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AutomaticAttendancePermissionDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun systemTextSizeKeepsTheMissingPermissionAndNextActionVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val actualScale = context.resources.configuration.fontScale
        InstrumentationRegistry.getArguments().getString("expectedFontScale")?.toFloat()?.let { expected ->
            assertTrue("Android must use the requested system font size", kotlin.math.abs(actualScale - expected) < 0.01f)
        }
        var requests = 0
        compose.setContent {
            CompanionTheme(AccentChoice.LAVENDER) {
                AutomaticAttendancePermissionDialog(
                    permissions = BetaSetupPermissions(true, false, true, false),
                    message = null,
                    onContinue = { requests += 1 },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("2 of 4 ready").assertIsDisplayed()
        compose.onNodeWithText("Notifications").assertIsDisplayed()
        compose.onNodeWithText("Location all the time").assertIsDisplayed()
        compose.onNodeWithText("Turn on notifications").assertIsDisplayed().performClick()
        compose.onNodeWithText("Not now").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, requests) }
        capture("permissions-${(actualScale * 100).toInt()}")
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "visual-review").apply { mkdirs() }
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
