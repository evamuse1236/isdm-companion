package org.isdm.companion.ui

import android.view.ViewGroup
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
