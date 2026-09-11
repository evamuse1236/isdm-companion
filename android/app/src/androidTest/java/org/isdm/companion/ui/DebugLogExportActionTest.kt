package org.isdm.companion.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DebugLogExportActionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun savePickerRequiresAChosenDocumentAndDoesNotSendAnything() {
        var saved: Uri? = null
        var requestCode = 0
        var launched: Intent? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                requestCode = code
                launched = contract.createIntent(compose.activity, input)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                CompanionTheme(AccentChoice.LAVENDER) { DebugLogExportAction(false) { saved = it } }
            }
        }
        compose.onNodeWithText("Save debug logs").performClick()
        compose.runOnIdle {
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, launched?.action)
            assertEquals("text/plain", launched?.type)
            assertNull(saved)
            registry.dispatchResult<Uri?>(requestCode, null)
            assertNull(saved)
        }
        compose.onNodeWithText("Save debug logs").performClick()
        val destination = Uri.parse("content://test-documents/chosen-debug-log")
        compose.runOnIdle { registry.dispatchResult(requestCode, destination) }
        compose.runOnIdle { assertEquals(destination, saved) }
    }
}
