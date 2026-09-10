package org.isdm.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AccentChoiceTest {
    @Test
    fun storedAccentRoundTrips() {
        AccentChoice.entries.forEach { accent ->
            assertEquals(accent, AccentChoice.fromStorageKey(accent.storageKey))
        }
    }

    @Test
    fun missingOrUnknownAccentFallsBackToLavender() {
        assertEquals(AccentChoice.LAVENDER, AccentChoice.fromStorageKey(null))
        assertEquals(AccentChoice.LAVENDER, AccentChoice.fromStorageKey("unknown"))
    }
}
