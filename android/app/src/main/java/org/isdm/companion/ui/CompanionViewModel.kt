package org.isdm.companion.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.isdm.companion.CompanionApplication
import org.isdm.companion.engine.Command
import org.isdm.companion.engine.CommandResult
import org.isdm.companion.engine.MonitoringMode
import org.isdm.companion.platform.MonitoringService

class CompanionViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CompanionApplication
    val state = app.engine.state

    private val _initializing = MutableStateFlow(true)
    val initializing = _initializing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = app.credentialStore.load()
            if (saved != null) {
                app.engine.dispatch(Command.ConfigureCredentials(saved.email, saved.password))
                app.engine.dispatch(Command.RefreshToday)
            }
            _initializing.value = false
        }
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            if (email.isBlank() || password.isBlank()) {
                _message.value = "Enter your LMS email and password."
                return@launch
            }
            _message.value = null
            app.engine.dispatch(Command.ConfigureCredentials(email, password))
            when (app.engine.dispatch(Command.RefreshToday)) {
                is CommandResult.Completed -> app.credentialStore.save(email, password)
                else -> Unit
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { app.engine.dispatch(Command.RefreshToday) }
    }

    fun mark(sessionId: String) {
        viewModelScope.launch { app.engine.dispatch(Command.Mark(sessionId)) }
    }

    fun handleMarkIntent(sessionId: String) {
        viewModelScope.launch {
            initializing.filter { !it }.first()
            if (state.value.sessions.none { it.nid == sessionId }) {
                app.engine.dispatch(Command.RefreshToday)
            }
            app.engine.dispatch(Command.Mark(sessionId))
        }
    }

    fun startMonitoring(mode: MonitoringMode = MonitoringMode.NOTIFY_ONLY) {
        viewModelScope.launch {
            val result = app.engine.dispatch(
                Command.ArmMonitoring(mode = mode),
            )
            if (result !is CommandResult.Completed) return@launch
            try {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, MonitoringService::class.java),
                )
            } catch (error: RuntimeException) {
                app.engine.dispatch(Command.DisarmMonitoring)
                _message.value = error.message ?: "Android could not start monitoring."
            }
        }
    }

    fun stopMonitoring() {
        viewModelScope.launch { app.engine.dispatch(Command.DisarmMonitoring) }
        app.stopService(Intent(app, MonitoringService::class.java))
    }

    fun clearCredentials() {
        stopMonitoring()
        app.credentialStore.clear()
        _message.value = "Saved LMS login removed."
    }

    fun clearMessage() {
        _message.value = null
    }
}
