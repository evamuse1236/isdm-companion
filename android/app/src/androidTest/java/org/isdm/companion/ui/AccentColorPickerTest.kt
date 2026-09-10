package org.isdm.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccentColorPickerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun selectingAccentUpdatesTheSelectedSwatch() {
        val selected = mutableStateOf(AccentChoice.LAVENDER)

        compose.setContent {
            Box(Modifier.width(320.dp)) {
                AccentColorPicker(
                    selected = selected.value,
                    onSelected = { selected.value = it },
                )
            }
        }

        compose.onNodeWithContentDescription("Lavender accent color")
            .assertIsSelected()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Slate accent color")
            .assertIsNotSelected()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()

        compose.onNodeWithContentDescription("Lavender accent color").assertIsNotSelected()
        compose.onNodeWithContentDescription("Slate accent color").assertIsSelected()
    }
}
