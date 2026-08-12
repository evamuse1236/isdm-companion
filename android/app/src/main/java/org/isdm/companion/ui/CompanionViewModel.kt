package org.isdm.companion.ui

import android.app.DownloadManager
import android.app.Application
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
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
import org.isdm.companion.engine.Credentials
import org.isdm.companion.engine.EngineError
import org.isdm.companion.engine.ReadingItem
import org.isdm.companion.domain.LocationGateReason
import org.isdm.companion.platform.AutoAttendanceScheduleResult
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.isdm.companion.platform.MonitoringService

class CompanionViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CompanionApplication
    val state = app.engine.state

    private val _initializing = MutableStateFlow(true)
    val initializing = _initializing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _autoAttendanceEnabled = MutableStateFlow(app.autoAttendanceStore.isEnabled())
    val autoAttendanceEnabled = _autoAttendanceEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = app.credentialStore.load()
            if (saved == null) {
                _initializing.value = false
                return@launch
            }
            app.engine.dispatch(Command.ConfigureCredentials(saved.email, saved.password))
            app.engine.dispatch(Command.RefreshToday)
            _initializing.value = false
            refreshScheduleAndReadings()
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
                is CommandResult.Completed -> {
                    app.credentialStore.save(email, password)
                    refreshScheduleAndReadings()
                }
                else -> Unit
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            app.engine.dispatch(Command.RefreshAll)
            scheduleAutoAttendance(showMessage = false)
        }
    }

    fun toggleReadingDone(readingId: String) {
        viewModelScope.launch { app.engine.dispatch(Command.ToggleReadingDone(readingId)) }
    }

    fun downloadReading(reading: ReadingItem) {
        viewModelScope.launch {
            val stored = app.credentialStore.load()
            if (stored == null) {
                _message.value = "Save your LMS login before downloading a reading."
                return@launch
            }
            runCatching {
                val url = app.lmsAdapter.readingDownloadUrl(
                    Credentials(stored.email, stored.password),
                    reading.sourceUrl,
                )
                val stamp = DOWNLOAD_STAMP.format(LocalDateTime.now())
                val fileName = readingDownloadFileName(reading.title, stamp)
                val request = DownloadManager.Request(Uri.parse(url))
                    .setTitle(reading.title)
                    .setDescription(reading.courseName)
                    .setMimeType("application/pdf")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                } else {
                    request.setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val downloadId = manager.enqueue(request)
                app.diagnostics.log("reading_download_enqueued", mapOf("download_id" to downloadId.toString()))
                fileName
            }.onSuccess { fileName ->
                _message.value = "Downloading $fileName"
            }.onFailure { error ->
                app.diagnostics.log("reading_download_failed", error = error)
                _message.value = error.message ?: "Could not download this reading."
            }
        }
    }

    fun mark(sessionId: String) {
        viewModelScope.launch {
            showMarkError(app.engine.dispatch(Command.Mark(sessionId)))
        }
    }

    fun handleMarkIntent(sessionId: String) {
        viewModelScope.launch {
            initializing.filter { !it }.first()
            if (state.value.sessions.none { it.nid == sessionId }) {
                app.engine.dispatch(Command.RefreshToday)
            }
            showMarkError(app.engine.dispatch(Command.Mark(sessionId)))
        }
    }

    private fun stopMonitoring() {
        viewModelScope.launch { app.engine.dispatch(Command.DisarmMonitoring) }
        app.stopService(Intent(app, MonitoringService::class.java))
    }

    fun setAutoAttendance(enabled: Boolean) {
        app.autoAttendanceStore.setEnabled(enabled)
        _autoAttendanceEnabled.value = enabled
        app.diagnostics.log("auto_attendance_preference_changed", mapOf("enabled" to enabled.toString()))
        if (!enabled) {
            app.autoAttendanceScheduler.cancel()
            stopMonitoring()
            _message.value = "Auto attendance is off."
            return
        }
        scheduleAutoAttendance(showMessage = true)
    }

    fun clearCredentials() {
        setAutoAttendance(false)
        app.credentialStore.clear()
        _message.value = "Saved LMS login removed."
    }

    fun clearMessage() {
        _message.value = null
    }

    fun showMessage(message: String) {
        _message.value = message
    }

    private fun showMarkError(result: CommandResult) {
        val error = (result as? CommandResult.Rejected)?.error ?: return
        _message.value = when (error) {
            is EngineError.AttendanceLocationDenied -> when (error.reason) {
                LocationGateReason.MISSING_EVIDENCE -> "Could not get a current location. Attendance remains locked."
                LocationGateReason.STALE_EVIDENCE -> "The phone location is too old. Try again."
                LocationGateReason.INACCURATE_EVIDENCE -> "The phone location is not precise enough. Move outdoors and try again."
                LocationGateReason.MOCK_LOCATION_EVIDENCE -> "Attendance is locked while a mock location is active."
                LocationGateReason.OUTSIDE_CAMPUS_ZONE -> "Attendance is available only inside the ISDM Campus Zone."
            }
            is EngineError.MarkWindowClosed -> "The LMS attendance window is closed."
            is EngineError.MarkRejected -> error.message
            else -> null
        }
    }

    private fun scheduleAutoAttendance(showMessage: Boolean) {
        val result = app.autoAttendanceScheduler.schedule(state.value.scheduleSessions, state.value.now)
        when (result) {
            AutoAttendanceScheduleResult.Disabled -> Unit
            AutoAttendanceScheduleResult.LocationPermissionRequired -> {
                app.diagnostics.log("auto_attendance_schedule_failed", mapOf("reason" to "location_permission"))
                if (showMessage) {
                    _message.value = "Auto attendance is on but locked until Precise and Allow all the time location access are enabled."
                }
            }
            AutoAttendanceScheduleResult.ExactAlarmPermissionRequired -> {
                app.diagnostics.log("auto_attendance_schedule_failed", mapOf("reason" to "exact_alarm_permission"))
                _message.value = "Android has not allowed precise class alarms, so auto attendance could not be scheduled."
            }
            is AutoAttendanceScheduleResult.Failed -> {
                app.diagnostics.log("auto_attendance_schedule_failed", error = result.error)
                _message.value = "Android could not schedule auto attendance."
            }
            is AutoAttendanceScheduleResult.Scheduled -> {
                app.diagnostics.log(
                    "auto_attendance_scheduled",
                    mapOf("windows" to result.windows.size.toString()),
                )
                if (showMessage) {
                    _message.value = if (result.windows.isEmpty()) {
                        "Auto attendance is on; no upcoming attendance sessions are loaded yet."
                    } else {
                        "Auto attendance is on for ${result.windows.size} upcoming sessions."
                    }
                }
            }
        }
    }

    private suspend fun refreshScheduleAndReadings() {
        app.engine.dispatch(Command.RefreshSchedule())
        scheduleAutoAttendance(showMessage = false)
        app.engine.dispatch(Command.RefreshReadings)
    }
}

internal fun readingDownloadFileName(title: String, suffix: String? = null): String {
    val base = title
        .replace(Regex("[^A-Za-z0-9._ -]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.', '_')
        .take(80)
        .ifBlank { "ISDM reading" }
    return if (suffix.isNullOrBlank()) "$base.pdf" else "$base-$suffix.pdf"
}

private val DOWNLOAD_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
