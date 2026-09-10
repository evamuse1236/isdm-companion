package org.isdm.companion.ui

import org.isdm.companion.engine.CompanionState
import org.isdm.companion.engine.EngineError

/** A saved login and its account cache survive temporary network failures. */
internal fun requiresLmsSignIn(state: CompanionState, hasSavedLogin: Boolean): Boolean =
    state.error is EngineError.AuthenticationFailed || (state.identity == null && !hasSavedLogin)
