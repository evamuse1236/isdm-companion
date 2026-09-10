package org.isdm.companion.ui

import java.time.Instant
import java.time.LocalDate
import org.isdm.companion.engine.CompanionState
import org.isdm.companion.engine.EngineError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginRecoveryPolicyTest {
    private val state = CompanionState(Instant.EPOCH, LocalDate.of(2026, 9, 11), credentialsConfigured = true)

    @Test fun `saved account can open before the first network response`() {
        assertFalse(requiresLmsSignIn(state, true))
        assertFalse(requiresLmsSignIn(state.copy(error = EngineError.NetworkFailure("timeout")), true))
    }

    @Test fun `only explicit authentication rejection asks saved user to log in`() {
        assertTrue(requiresLmsSignIn(state.copy(error = EngineError.AuthenticationFailed("rejected")), true))
        assertTrue(requiresLmsSignIn(state, false))
    }
}
