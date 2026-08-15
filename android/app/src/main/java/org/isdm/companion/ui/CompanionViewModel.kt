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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.isdm.companion.CompanionApplication
import org.isdm.companion.engine.Command
import org.isdm.companion.engine.CommandResult
import org.isdm.companion.engine.Credentials
import org.isdm.companion.engine.EngineError
import org.isdm.companion.engine.ReadingItem
import org.isdm.companion.domain.LocationGateReason
import org.isdm.companion.platform.AutoAttendanceScheduleResult
import org.isdm.companion.platform.BetaApiException
import org.isdm.companion.platform.CompanionSyncWorker
import org.isdm.companion.platform.clearLmsBrowserSessionAndWait
import java.time.Instant
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

    private val _betaEnrolled = MutableStateFlow(app.betaManager.isEnrolled)
    val betaEnrolled = _betaEnrolled.asStateFlow()

    private val _betaEnrolling = MutableStateFlow(false)
    val betaEnrolling = _betaEnrolling.asStateFlow()

    private val _reportSending = MutableStateFlow(false)
    val reportSending = _reportSending.asStateFlow()

    private val _signingOut = MutableStateFlow(false)
    val signingOut = _signingOut.asStateFlow()

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

    fun enrollBeta(
        inviteCode: String,
        section: String,
        plc: String?,
        consented: Boolean,
        onSuccess: () -> Unit,
    ) {
        if (!isBetaEnrollmentValid(inviteCode, section, consented) || _betaEnrolling.value) return
        _betaEnrolling.value = true
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { app.betaManager.enroll(inviteCode, section, plc) }
            }.onSuccess { installation ->
                _betaEnrolled.value = true
                if (app.autoAttendanceStore.setEnabled(true)) _autoAttendanceEnabled.value = true
                _message.value = "${installation.testerCode} joined the beta. Sign in to the LMS next."
                onSuccess()
            }.onFailure { error ->
                app.diagnostics.log("beta_enrollment_failed", error = error)
                _message.value = when ((error as? BetaApiException)?.code) {
                    "invalid_invite" -> "That invite code is not valid."
                    "invite_already_claimed" -> "That invite code has already been used."
                    else -> "Could not join the beta. Check the internet connection and try again."
                }
            }
            _betaEnrolling.value = false
        }
    }

    fun submitBetaReport(
        category: String,
        description: String,
        images: List<Uri>,
        onSuccess: () -> Unit,
    ) {
        if (!isBetaReportValid(category, description) || _reportSending.value) return
        _reportSending.value = true
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    app.betaManager.submitReport(
                        category = category,
                        title = betaReportTitle(category, description),
                        description = description,
                        imageUris = images,
                    )
                }
            }.onSuccess { result ->
                app.diagnostics.log(
                    "beta_report_sent",
                    mapOf(
                        "category" to category,
                        "attachment_count" to result.attachmentsUploaded.toString(),
                    ),
                )
                _message.value = "Sent to the beta dashboard."
                onSuccess()
            }.onFailure { error ->
                app.diagnostics.log("beta_report_failed", mapOf("category" to category), error)
                _message.value = error.message?.takeIf { it.contains("5 MB") }
                    ?: "Could not send the report. Check the internet connection and try again."
            }
            _reportSending.value = false
        }
    }

    fun hasScheduleFeedback(date: java.time.LocalDate, sessionCount: Int): Boolean =
        app.betaManager.hasScheduleFeedback(date.toString(), sessionCount)

    fun confirmSchedule(date: java.time.LocalDate, correct: Boolean, note: String?) {
        val sessions = state.value.scheduleSessions.filter { it.start.atZone(org.isdm.companion.engine.LMS_ZONE).toLocalDate() == date }
        app.betaManager.queueScheduleFeedback(
            status = if (correct) "confirmed" else "mismatch",
            selectedDate = date.toString(),
            sessionCount = sessions.size,
            note = note?.trim()?.takeIf(String::isNotEmpty),
            detectedCohorts = sessions.mapNotNullTo(mutableSetOf()) { it.cohort },
        )
        _message.value = "Thanks — schedule feedback queued."
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
        if (!app.autoAttendanceStore.setEnabled(enabled)) {
            _autoAttendanceEnabled.value = app.autoAttendanceStore.isEnabled()
            _message.value = "Android could not save the auto-attendance setting. Try again."
            return
        }
        _autoAttendanceEnabled.value = enabled
        app.diagnostics.log("auto_attendance_preference_changed", mapOf("enabled" to enabled.toString()))
        if (!enabled) {
            val alarmsCancelled = app.autoAttendanceScheduler.cancel()
            stopMonitoring()
            _message.value = if (alarmsCancelled) {
                "Auto attendance is off."
            } else {
                "Auto attendance is off. Android could not remove every pending alarm, but they will be ignored."
            }
            return
        }
        scheduleAutoAttendance(showMessage = true)
    }

    fun signOut() {
        if (_signingOut.value) return
        _signingOut.value = true
        viewModelScope.launch {
            val failures = runCatching {
                withContext(NonCancellable) {
                    val failed = mutableListOf<String>()
                    if (!app.autoAttendanceStore.setEnabled(false)) failed += "auto-attendance setting"
                    _autoAttendanceEnabled.value = false
                    if (!app.autoAttendanceScheduler.cancel()) failed += "pending alarms"
                    app.stopService(Intent(app, MonitoringService::class.java))

                    val backgroundWorkStopped = withContext(Dispatchers.IO) {
                        runCatching {
                            CompanionSyncWorker.cancelAll(app).forEach { operation -> operation.result.get() }
                        }.isSuccess
                    }
                    if (!backgroundWorkStopped) failed += "background sync"
                    if (!app.credentialStore.clear()) failed += "saved LMS login"
                    if (app.engine.dispatch(Command.SignOut) !is CommandResult.Completed) failed += "live LMS session"
                    if (!clearLmsBrowserSessionAndWait()) failed += "browser session"
                    if (backgroundWorkStopped && app.credentialStore.load() == null) {
                        CompanionSyncWorker.schedule(app)
                    }
                    failed
                }
            }.getOrElse { error ->
                app.diagnostics.log("sign_out_failed", error = error)
                listOf("unexpected cleanup error")
            }
            _message.value = if (failures.isEmpty()) {
                "Signed out. Saved LMS login and browser session removed."
            } else {
                "Sign-out incomplete (${failures.joinToString()}). Try again before closing the app."
            }
            _signingOut.value = false
        }
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
        val result = app.autoAttendanceScheduler.schedule(state.value.scheduleSessions, Instant.now())
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
