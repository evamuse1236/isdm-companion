package org.isdm.companion.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingActivityContractTest {
    @Test
    fun missingExternalBrowserDoesNotCrashTheReadingScreen() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val unavailableBrowser = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent?) {
                throw ActivityNotFoundException("No browser")
            }
        }

        assertFalse(openExternalUrlSafely(unavailableBrowser, "mailto:faculty@example.org"))
    }
}
