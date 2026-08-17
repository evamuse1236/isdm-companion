package org.isdm.companion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.isdm.companion.CompanionApplication
import org.isdm.companion.R
import org.isdm.companion.engine.CompanionState
import org.isdm.companion.engine.EngineError
import org.isdm.companion.engine.FacultyProfile
import org.isdm.companion.engine.LmsReadingProgress
import org.isdm.companion.engine.ReadingItem
import org.isdm.companion.engine.Session
import org.isdm.companion.engine.SessionState
import org.isdm.companion.engine.planCourseReadings
import org.isdm.companion.engine.defaultReadingCourseId
import org.isdm.companion.domain.LocationGateReason
import org.isdm.companion.platform.BetaProfile
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: CompanionViewModel by viewModels()
    private val diagnostics
        get() = (application as CompanionApplication).diagnostics
    private var pendingMarkSessionId: String? = null
    private var pendingMarkFromIntent = false
    private var pendingAutoAttendance = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        diagnostics.log(
            "permission_result",
            mapOf("granted" to granted.toString(), "permission" to "notifications"),
        )
        if (pendingAutoAttendance) {
            pendingAutoAttendance = false
            viewModel.setAutoAttendance(true)
            viewModel.markSetupExplained()
            publishSetupPermissions()
        }
    }

    private val foregroundLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val preciseGranted = hasPreciseLocationAccess()
        diagnostics.log(
            "permission_result",
            mapOf(
                "granted" to preciseGranted.toString(),
                "permission" to "precise_location",
                "purpose" to if (pendingAutoAttendance) "auto_attendance" else "manual_mark",
            ),
        )
        if (preciseGranted) {
            completePendingMark()
            if (pendingAutoAttendance) requestBackgroundLocationAccess()
        } else {
            pendingMarkSessionId = null
            pendingMarkFromIntent = false
            if (pendingAutoAttendance) {
                pendingAutoAttendance = false
                viewModel.setAutoAttendance(false)
                viewModel.markSetupExplained()
            }
            publishSetupPermissions()
            viewModel.showMessage("Attendance is locked until Precise location is allowed.")
        }
    }

    private val backgroundLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        diagnostics.log(
            "permission_result",
            mapOf("granted" to granted.toString(), "permission" to "background_location"),
        )
        finishAutoAttendancePermissionFlow()
    }

    private val appLocationSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        diagnostics.log(
            "permission_settings_returned",
            mapOf("background_location_granted" to hasBackgroundLocationAccess().toString()),
        )
        finishAutoAttendancePermissionFlow()
    }

    private val exactAlarmSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        diagnostics.log(
            "permission_settings_returned",
            mapOf("exact_alarms_granted" to canScheduleExactAlarms().toString()),
        )
        finishExactAlarmPermissionFlow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { restored ->
            val pending = readPendingActivityActions(restored)
            pendingMarkSessionId = pending.markSessionId
            pendingMarkFromIntent = pending.markFromIntent
            pendingAutoAttendance = pending.autoAttendance
        }
        diagnostics.log("main_activity_created", mapOf("restored" to (savedInstanceState != null).toString()))
        setContent {
            MaterialTheme(colorScheme = companionColors(), typography = companionTypography) {
                CompanionScreen(
                    viewModel = viewModel,
                    onAutoAttendance = ::requestAutoAttendance,
                    onMarkAttendance = { requestMark(it) },
                    onOpenReading = { url ->
                        startActivity(
                            Intent(this, ReadingActivity::class.java)
                                .putExtra(ReadingActivity.EXTRA_SOURCE_URL, url),
                        )
                    },
                )
            }
        }
        handleIntent(intent)
        val companionApp = application as CompanionApplication
        val autoAttendanceEnabled = companionApp.autoAttendanceStore.isEnabled()
        val setupReadyToResume = companionApp.betaManager.setupExplained && !companionApp.betaManager.shouldShowTour
        if (savedInstanceState == null && autoAttendanceEnabled && setupReadyToResume && pendingMarkSessionId == null) {
            lifecycleScope.launch {
                viewModel.initializing.filter { !it }.first()
                if (pendingMarkSessionId == null) requestAutoAttendance(true)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        publishSetupPermissions()
        val app = application as CompanionApplication
        if (!pendingAutoAttendance && app.autoAttendanceStore.isEnabled() &&
            (!hasBackgroundLocationAccess() || !canScheduleExactAlarms())
        ) {
            viewModel.setAutoAttendance(false)
            viewModel.showMessage("Needs attention: automatic attendance stopped because required Android access was removed.")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        writePendingActivityActions(
            outState,
            PendingActivityActions(
                markSessionId = pendingMarkSessionId,
                markFromIntent = pendingMarkFromIntent,
                autoAttendance = pendingAutoAttendance,
            ),
        )
        super.onSaveInstanceState(outState)
    }

    private fun handleIntent(intent: Intent?) {
        val nid = intent?.getStringExtra(EXTRA_MARK_NID) ?: return
        intent.removeExtra(EXTRA_MARK_NID)
        if (!isTrustedAttendanceIntentTarget(intent.component?.className)) {
            diagnostics.log("attendance_intent_rejected", mapOf("reason" to "exported_component"))
            return
        }
        requestMark(nid, fromIntent = true)
    }

    private fun requestAutoAttendance(enabled: Boolean) {
        if (!enabled) {
            pendingAutoAttendance = false
            viewModel.setAutoAttendance(false)
            return
        }
        if (!viewModel.hasDetectedSectionForAutomaticAttendance()) {
            viewModel.setAutoAttendance(false)
            viewModel.markSetupExplained()
            viewModel.showMessage("Needs attention: the LMS must detect your Section before automatic attendance can run.")
            return
        }
        pendingAutoAttendance = true
        if (!hasPreciseLocationAccess()) {
            diagnostics.log(
                "permission_requested",
                mapOf("permission" to "precise_location", "purpose" to "auto_attendance"),
            )
            foregroundLocationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        } else {
            requestBackgroundLocationAccess()
        }
    }

    private fun requestMark(sessionId: String, fromIntent: Boolean = false) {
        if (hasPreciseLocationAccess()) {
            if (fromIntent) viewModel.handleMarkIntent(sessionId) else viewModel.mark(sessionId)
            return
        }
        pendingMarkSessionId = sessionId
        pendingMarkFromIntent = fromIntent
        diagnostics.log(
            "permission_requested",
            mapOf("permission" to "precise_location", "purpose" to "manual_mark"),
        )
        foregroundLocationPermission.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    private fun completePendingMark() {
        val sessionId = pendingMarkSessionId ?: return
        val fromIntent = pendingMarkFromIntent
        pendingMarkSessionId = null
        pendingMarkFromIntent = false
        if (fromIntent) viewModel.handleMarkIntent(sessionId) else viewModel.mark(sessionId)
    }

    private fun requestBackgroundLocationAccess() {
        if (hasBackgroundLocationAccess()) {
            finishAutoAttendancePermissionFlow()
            return
        }
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> finishAutoAttendancePermissionFlow()
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                diagnostics.log("permission_requested", mapOf("permission" to "background_location"))
                backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            else -> {
                diagnostics.log(
                    "permission_settings_opened",
                    mapOf("permission" to "background_location"),
                )
                viewModel.showMessage("In Android settings, set Location to Allow all the time, then return.")
                appLocationSettings.launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                )
            }
        }
    }

    private fun finishAutoAttendancePermissionFlow() {
        if (!pendingAutoAttendance) return
        if (!hasBackgroundLocationAccess()) {
            pendingAutoAttendance = false
            viewModel.setAutoAttendance(false)
            viewModel.markSetupExplained()
            publishSetupPermissions()
            viewModel.showMessage("Needs attention: set Location to Allow all the time before automatic attendance can run.")
            return
        }
        if (!canScheduleExactAlarms()) {
            diagnostics.log("permission_settings_opened", mapOf("permission" to "exact_alarms"))
            viewModel.showMessage("Allow precise alarms so attendance checks can start at class time.")
            exactAlarmSettings.launch(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")),
            )
            return
        }
        finishExactAlarmPermissionFlow()
    }

    private fun finishExactAlarmPermissionFlow() {
        if (!pendingAutoAttendance) return
        if (!canScheduleExactAlarms()) {
            pendingAutoAttendance = false
            viewModel.setAutoAttendance(false)
            viewModel.markSetupExplained()
            publishSetupPermissions()
            viewModel.showMessage("Needs attention: precise alarms are required for automatic attendance.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            diagnostics.log("permission_requested", mapOf("permission" to "notifications"))
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            pendingAutoAttendance = false
            viewModel.setAutoAttendance(true)
            viewModel.markSetupExplained()
            publishSetupPermissions()
        }
    }

    private fun hasPreciseLocationAccess(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocationAccess(): Boolean =
        hasPreciseLocationAccess() && (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            )

    private fun canScheduleExactAlarms(): Boolean =
        (application as CompanionApplication).autoAttendanceScheduler.canScheduleExactAlarms()

    private fun publishSetupPermissions() {
        viewModel.updateSetupPermissions(
            BetaSetupPermissions(
                preciseLocation = hasPreciseLocationAccess(),
                backgroundLocation = hasBackgroundLocationAccess(),
                exactAlarms = canScheduleExactAlarms(),
                notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            ),
        )
    }

    companion object {
        const val EXTRA_MARK_NID = "mark_nid"
    }
}

internal fun isTrustedAttendanceIntentTarget(componentClassName: String?): Boolean =
    componentClassName == MainActivity::class.java.name

internal data class PendingActivityActions(
    val markSessionId: String?,
    val markFromIntent: Boolean,
    val autoAttendance: Boolean,
)

internal fun writePendingActivityActions(bundle: Bundle, actions: PendingActivityActions) {
    bundle.putString(PENDING_MARK_SESSION_ID, actions.markSessionId)
    bundle.putBoolean(PENDING_MARK_FROM_INTENT, actions.markFromIntent)
    bundle.putBoolean(PENDING_AUTO_ATTENDANCE, actions.autoAttendance)
}

internal fun readPendingActivityActions(bundle: Bundle): PendingActivityActions = PendingActivityActions(
    markSessionId = bundle.getString(PENDING_MARK_SESSION_ID),
    markFromIntent = bundle.getBoolean(PENDING_MARK_FROM_INTENT),
    autoAttendance = bundle.getBoolean(PENDING_AUTO_ATTENDANCE),
)

private const val PENDING_MARK_SESSION_ID = "pending_mark_session_id"
private const val PENDING_MARK_FROM_INTENT = "pending_mark_from_intent"
private const val PENDING_AUTO_ATTENDANCE = "pending_auto_attendance"

private enum class Destination { SCHEDULE, READINGS, PROFILE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionScreen(
    viewModel: CompanionViewModel,
    onAutoAttendance: (Boolean) -> Unit,
    onMarkAttendance: (String) -> Unit,
    onOpenReading: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val initializing by viewModel.initializing.collectAsStateWithLifecycle()
    val signingOut by viewModel.signingOut.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val autoAttendanceEnabled by viewModel.autoAttendanceEnabled.collectAsStateWithLifecycle()
    val betaEnrolled by viewModel.betaEnrolled.collectAsStateWithLifecycle()
    val betaEnrolling by viewModel.betaEnrolling.collectAsStateWithLifecycle()
    val betaTourRequired by viewModel.betaTourRequired.collectAsStateWithLifecycle()
    val betaProfile by viewModel.betaProfile.collectAsStateWithLifecycle()
    val setupExplained by viewModel.setupExplained.collectAsStateWithLifecycle()
    val setupPermissions by viewModel.setupPermissions.collectAsStateWithLifecycle()
    val reportSending by viewModel.reportSending.collectAsStateWithLifecycle()
    val attendanceFeedback by viewModel.attendanceFeedback.collectAsStateWithLifecycle()
    val releaseNotes by viewModel.releaseNotes.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val rootView = LocalView.current
    var destination by rememberSaveable { mutableStateOf(Destination.SCHEDULE) }
    var selectedDate by rememberSaveable { mutableStateOf(state.today) }
    var selectedSession by remember { mutableStateOf<Session?>(null) }
    var selectedReading by remember { mutableStateOf<ReadingItem?>(null) }
    var issueReportOpen by rememberSaveable { mutableStateOf(false) }
    var signOutOpen by rememberSaveable { mutableStateOf(false) }
    val currentDetected = detectedProfileCohorts(state)
    val detectionLoaded = state.scheduleSync.lastSuccess != null
    val confirmedDetectionCurrent = betaProfile != null && if (detectionLoaded) {
        currentDetected.first.isNotEmpty() && betaProfile?.detectedSections == currentDetected.first &&
            betaProfile?.detectedGroups == currentDetected.second
    } else {
        betaProfile?.detectedSections?.isNotEmpty() == true
    }
    val setupStatus = betaSetupStatus(
        consented = betaProfile != null,
        profileKnown = confirmedDetectionCurrent,
        permissions = setupPermissions,
    )

    LaunchedEffect(state.today) {
        if (selectedDate < state.scheduleStart || selectedDate >= state.scheduleEndExclusive) {
            selectedDate = state.today
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(attendanceFeedback?.token) {
        attendanceFeedback?.let {
            performAttendanceHaptic(rootView, it.success)
            viewModel.consumeAttendanceFeedback(it.token)
        }
    }

    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            val mainExperienceVisible = !initializing && !signingOut && !betaTourRequired &&
                state.identity != null && betaEnrolled && betaProfile != null && setupExplained
            if (mainExperienceVisible) {
                SmallFloatingActionButton(
                    onClick = { issueReportOpen = true },
                    containerColor = Teal,
                    contentColor = Color.White,
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Default.BugReport,
                        contentDescription = "Report an issue",
                    )
                }
            }
        },
    ) { padding ->
        when {
            initializing || signingOut -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = Teal) }

            betaTourRequired -> BetaTourContent(
                onFinish = viewModel::finishTour,
                modifier = Modifier.padding(padding),
            )

            state.identity == null -> LoginContent(
                state = state,
                onSignIn = viewModel::signIn,
                modifier = Modifier.padding(padding),
            )

            !betaEnrolled -> BetaEnrollmentContent(
                enrolling = betaEnrolling,
                detectedName = state.identity?.displayName.orEmpty(),
                detectedSections = detectedProfileCohorts(state).first,
                detectedGroups = detectedProfileCohorts(state).second,
                onEnroll = { inviteCode, name, section, plc, detectedSections, detectedGroups, consented ->
                    viewModel.enrollBeta(inviteCode, name, section, plc, detectedSections, detectedGroups, consented) {
                        Unit
                    }
                },
                modifier = Modifier.padding(padding),
            )

            betaProfile == null || profileDetectionChanged(betaProfile, currentDetected, detectionLoaded) -> BetaProfileContent(
                initialName = if (betaProfile == null) state.identity?.displayName.orEmpty() else betaProfile?.supportName.orEmpty(),
                initialSection = betaProfile?.selfSection
                    ?: currentDetected.first.firstOrNull()?.let { "Section $it" }.orEmpty(),
                initialGroup = betaProfile?.selfPlc
                    ?: currentDetected.second.firstOrNull()?.let { "Group $it" }.orEmpty(),
                detectedSections = detectedProfileCohorts(state).first,
                detectedGroups = detectedProfileCohorts(state).second,
                supportNameRequired = betaProfile == null,
                onSave = { name, section, group ->
                    viewModel.updateBetaProfile(
                        name, section, group,
                        detectedProfileCohorts(state).first,
                        detectedProfileCohorts(state).second,
                    ) {}
                },
                modifier = Modifier.padding(padding),
            )

            !setupExplained -> AutoAttendanceEducation(
                onContinue = {
                    onAutoAttendance(true)
                },
                modifier = Modifier.padding(padding),
            )

            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                CompanionHeader(
                    state = state,
                    autoAttendanceEnabled = autoAttendanceEnabled,
                    onRefresh = viewModel::refresh,
                    onAutoAttendance = onAutoAttendance,
                )
                DestinationTabs(destination, onChange = { destination = it })
                AnimatedContent(
                    targetState = destination,
                    transitionSpec = {
                        (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) + slideInVertically { it / 18 })
                            .togetherWith(fadeOut() + slideOutVertically { -it / 18 })
                    },
                    label = "destination",
                    modifier = Modifier.weight(1f),
                ) { current ->
                    if (current == Destination.SCHEDULE) {
                        ScheduleContent(
                            state = state,
                            selectedDate = selectedDate,
                            onStepDate = { delta ->
                                val candidate = selectedDate.plusDays(delta.toLong())
                                if (candidate >= state.scheduleStart && candidate < state.scheduleEndExclusive) {
                                    selectedDate = candidate
                                }
                            },
                            onSession = { selectedSession = it },
                            onMark = onMarkAttendance,
                            onSignOut = { signOutOpen = true },
                            scheduleFeedbackRecorded = viewModel.hasScheduleFeedback(
                                selectedDate,
                                state.scheduleSessions.count { it.start.atZone(IST).toLocalDate() == selectedDate },
                            ),
                            onScheduleFeedback = { correct, note ->
                                viewModel.confirmSchedule(selectedDate, correct, note)
                            },
                            setupStatus = setupStatus,
                            autoAttendanceEnabled = autoAttendanceEnabled,
                            onOpenProfile = { destination = Destination.PROFILE },
                        )
                    } else if (current == Destination.READINGS) {
                        ReadingsContent(
                            state = state,
                            onReading = { selectedReading = it },
                            onFacultyProfile = { onOpenReading(it.sourceUrl) },
                            onToggleDone = viewModel::toggleReadingDone,
                        )
                    } else {
                        ProfileContent(
                            state = state,
                            profile = betaProfile,
                            autoAttendanceEnabled = autoAttendanceEnabled,
                            setupStatus = setupStatus,
                            detectedSections = detectedProfileCohorts(state).first,
                            detectedGroups = detectedProfileCohorts(state).second,
                            onAutoAttendance = onAutoAttendance,
                            onSaveProfile = { name, section, group ->
                                viewModel.updateBetaProfile(
                                    name, section, group,
                                    detectedProfileCohorts(state).first,
                                    detectedProfileCohorts(state).second,
                                ) {}
                            },
                            onDeleteName = { viewModel.deleteBetaSupportName {} },
                            onReopenTour = viewModel::reopenTour,
                            onRefreshAttendance = viewModel::refreshAttendance,
                            onShowReleaseNotes = viewModel::showReleaseNotes,
                            onSignOut = { signOutOpen = true },
                        )
                    }
                }
            }
        }
    }

    selectedSession?.let { session ->
        LectureSheet(session, state, onDismiss = { selectedSession = null }, onMark = onMarkAttendance)
    }
    selectedReading?.let { reading ->
        ReadingSheet(
            reading = state.readings.firstOrNull { it.vid == reading.vid } ?: reading,
            onDismiss = { selectedReading = null },
            onOpen = onOpenReading,
            onDownload = viewModel::downloadReading,
            onToggleDone = viewModel::toggleReadingDone,
        )
    }
    if (issueReportOpen) {
        IssueReportSheet(
            onDismiss = { issueReportOpen = false },
            sending = reportSending,
            onSend = { category, description, images ->
                viewModel.submitBetaReport(category, description, images) {
                    issueReportOpen = false
                }
            },
        )
    }
    if (signOutOpen) {
        AlertDialog(
            onDismissRequest = { signOutOpen = false },
            title = { Text("Sign out?") },
            text = { Text("This removes your saved LMS login and browser session from this phone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        signOutOpen = false
                        viewModel.signOut()
                    },
                ) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { signOutOpen = false }) { Text("Cancel") }
            },
        )
    }
    releaseNotes?.let { notes ->
        AlertDialog(
            onDismissRequest = viewModel::dismissReleaseNotes,
            title = { Text("What’s new in ${notes.versionName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    notes.items.forEach { item -> Text("• $item", lineHeight = 20.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissReleaseNotes) { Text("Got it") }
            },
        )
    }
}

@Composable
private fun CompanionHeader(
    state: CompanionState,
    autoAttendanceEnabled: Boolean,
    onRefresh: () -> Unit,
    onAutoAttendance: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = autoAttendanceEnabled,
                onCheckedChange = onAutoAttendance,
                modifier = Modifier.size(width = 40.dp, height = 24.dp),
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Teal,
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = Soft,
                    uncheckedThumbColor = Color.White,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Text("Auto attendance", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Text(
                if (autoAttendanceEnabled) "On" else "Off",
                fontSize = 10.sp,
                color = if (autoAttendanceEnabled) Teal else Muted,
                fontWeight = FontWeight.Bold,
            )
        }
        val syncing = state.sync.inProgress || state.scheduleSync.inProgress ||
            state.readingSync.inProgress || state.attendanceSync.inProgress
        val latest = listOfNotNull(
            state.sync.lastSuccess,
            state.scheduleSync.lastSuccess,
            state.readingSync.lastSuccess,
            state.attendanceSync.lastSuccess,
        ).maxOrNull()
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = !syncing, onClick = onRefresh).padding(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(if (syncing) SkyAccent else Green, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                if (syncing) "Updating…" else latest?.let { "Updated ${TIME_FORMAT.format(it.atZone(IST))}" } ?: "Update",
                fontSize = 11.sp,
                color = if (syncing) SkyAccent else Green,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun DestinationTabs(destination: Destination, onChange: (Destination) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Destination.entries.forEach { item ->
            val active = item == destination
            val color by animateColorAsState(if (active) TealDeep else Soft, label = "tab")
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(color)
                    .clickable { onChange(item) }.padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (item) {
                        Destination.SCHEDULE -> "Schedule"
                        Destination.READINGS -> "Readings"
                        Destination.PROFILE -> "Profile"
                    },
                    color = if (active) Color.White else Muted,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ScheduleContent(
    state: CompanionState,
    selectedDate: LocalDate,
    onStepDate: (Int) -> Unit,
    onSession: (Session) -> Unit,
    onMark: (String) -> Unit,
    onSignOut: () -> Unit,
    scheduleFeedbackRecorded: Boolean,
    onScheduleFeedback: (Boolean, String?) -> Unit,
    setupStatus: BetaSetupStatus,
    autoAttendanceEnabled: Boolean,
    onOpenProfile: () -> Unit,
) {
    val source = state.scheduleSessions.ifEmpty { state.sessions }
    val sessions = source.filter { it.start.atZone(IST).toLocalDate() == selectedDate }
    var clockNow by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            clockNow = Instant.now()
            delay(1_000)
        }
    }
    val highlights = scheduleHighlights(source, selectedDate, state.today, clockNow)
        .associate { it.session to it.kind }
    var mismatchOpen by rememberSaveable(selectedDate) { mutableStateOf(false) }
    var mismatchNote by rememberSaveable(selectedDate) { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 28.dp),
    ) {
        item {
            DateHeader(
                date = selectedDate,
                canPrevious = selectedDate > state.scheduleStart,
                canNext = selectedDate.plusDays(1) < state.scheduleEndExclusive,
                onStep = onStepDate,
            )
        }
        item {
            val label = when {
                !setupStatus.canEnableAutomaticAttendance -> "Setup check · Needs attention"
                autoAttendanceEnabled -> "Setup check · Ready"
                else -> "Setup check · Automatic attendance off"
            }
            val needsAttention = !setupStatus.canEnableAutomaticAttendance
            Text(
                label,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    .background(if (!needsAttention) Mint else Butter, RoundedCornerShape(12.dp))
                    .clickable(enabled = needsAttention, onClick = onOpenProfile).padding(11.dp),
                color = if (!needsAttention) Green else Ink,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        if (sessions.isNotEmpty() && !scheduleFeedbackRecorded) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(16.dp)).border(1.dp, Line, RoundedCornerShape(16.dp))
                        .background(Color.White).padding(16.dp),
                ) {
                    Text("Is this your section and PLC day?", color = Ink, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(5.dp))
                    Text("Check the sessions below against your LMS schedule.", color = Muted, fontSize = 12.sp)
                    if (mismatchOpen) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = mismatchNote,
                            onValueChange = { mismatchNote = it },
                            label = { Text("What's missing or incorrect?") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { mismatchOpen = false }) { Text("Cancel") }
                            Button(
                                onClick = { onScheduleFeedback(false, mismatchNote) },
                                enabled = mismatchNote.isNotBlank(),
                            ) { Text("Send") }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { mismatchOpen = true }) { Text("Wrong section or PLC") }
                            Button(onClick = { onScheduleFeedback(true, null) }) { Text("Looks right") }
                        }
                    }
                }
            }
        }
        if (state.scheduleSync.inProgress && source.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Teal) } }
        } else if (sessions.isEmpty()) {
            item { EmptyState("Nothing scheduled", "This day is clear. Your readings remain available offline.") }
        } else {
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, Line, RoundedCornerShape(20.dp)).background(Color.White),
                ) {
                    sessions.forEachIndexed { index, session ->
                        if (index > 0) HorizontalDivider(color = Line)
                        SessionRow(
                            session = session,
                            highlight = highlights[session],
                            now = clockNow,
                            onOpen = { onSession(session) },
                            onMark = onMark,
                        )
                    }
                }
            }
        }
        state.scheduleSync.error?.let { error ->
            item { InlineError("Schedule may be stale. ${errorText(error)}") }
        }
        item {
            TextButton(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            ) { Text("Sign out", color = Muted) }
        }
    }
}

@Composable
private fun DateHeader(date: LocalDate, canPrevious: Boolean, canNext: Boolean, onStep: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(DATE_FORMAT.format(date), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DateArrow(false, canPrevious) { onStep(-1) }
            DateArrow(true, canNext) { onStep(1) }
        }
    }
}

@Composable
private fun DateArrow(forward: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).alpha(if (enabled) 1f else .38f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            if (forward) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = if (forward) "Next day" else "Previous day",
            tint = Ink,
        )
    }
}

@Composable
private fun SessionRow(
    session: Session,
    highlight: ScheduleHighlightKind?,
    now: java.time.Instant,
    onOpen: () -> Unit,
    onMark: (String) -> Unit,
) {
    val focus = highlight != null
    val background by animateColorAsState(if (focus) Blush else Color.White, label = "session focus")
    val past = session.state == SessionState.DONE || session.state == SessionState.MISSED
    Row(
        Modifier.fillMaxWidth().background(background).clickable(onClick = onOpen).alpha(if (past && !focus) .48f else 1f),
    ) {
        Text(
            TIME_SHORT.format(session.start.atZone(IST)),
            modifier = Modifier.width(76.dp).padding(start = 16.dp, top = if (focus) 18.dp else 16.dp, bottom = 16.dp),
            color = if (focus) BlushInk else Teal,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        if (focus) {
            Box(Modifier.weight(1f)) {
                Column(Modifier.fillMaxWidth().padding(start = 2.dp, top = 17.dp, end = 24.dp, bottom = 17.dp)) {
                    val minutes = Duration.between(now, session.start).toMinutes().coerceAtLeast(0)
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = BlushAccent, fontWeight = FontWeight.ExtraBold)) {
                                append(if (highlight == ScheduleHighlightKind.HAPPENING_NOW) "Happening now · " else "Up next · ")
                            }
                            withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(session.name) }
                            if (highlight == ScheduleHighlightKind.UP_NEXT) {
                                append(" starts in ")
                                withStyle(SpanStyle(color = BlushAccent, fontWeight = FontWeight.ExtraBold)) { append(formatSessionCountdown(minutes)) }
                                append(".")
                            } else {
                                append(" is in session until ${TIME_FORMAT.format(session.end.atZone(IST))}.")
                            }
                        },
                        fontSize = 18.sp,
                        lineHeight = 20.sp,
                        color = BlushInk,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(session.metaLine(), fontSize = 11.sp, color = BlushInk.copy(alpha = .66f), fontWeight = FontWeight.SemiBold)
                    if (session.endEstimated) {
                        Text("Estimated end", fontSize = 10.sp, color = BlushAccent, fontWeight = FontWeight.Bold)
                    }
                    AnimatedVisibility(
                        visible = session.state == SessionState.OPEN && session.nid != null,
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut() + slideOutVertically { it / 2 },
                    ) {
                        Button(
                            onClick = { session.nid?.let(onMark) },
                            colors = ButtonDefaults.buttonColors(containerColor = Ink),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                            modifier = Modifier.padding(top = 12.dp),
                        ) { Text("Mark attendance", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                Canvas(Modifier.align(Alignment.BottomEnd).size(100.dp, 72.dp)) {
                    drawCircle(BlushShape.copy(alpha = .44f), radius = 33.dp.toPx(), center = Offset(size.width * .47f, size.height * .88f))
                    drawCircle(BlushShape.copy(alpha = .44f), radius = 33.dp.toPx(), center = Offset(size.width * .86f, size.height * .68f))
                }
            }
        } else {
            Column(Modifier.weight(1f).padding(top = 15.dp, end = 14.dp, bottom = 15.dp)) {
                Text(session.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(4.dp))
                Text(session.metaLine(), fontSize = 11.sp, color = Muted, maxLines = 1)
            }
        }
    }
}

internal fun formatSessionCountdown(minutes: Long): String {
    val safeMinutes = minutes.coerceAtLeast(0)
    if (safeMinutes <= 60) return "$safeMinutes ${if (safeMinutes == 1L) "minute" else "minutes"}"
    val hours = safeMinutes / 60
    val remainder = safeMinutes % 60
    return if (remainder == 0L) "${hours}h" else "${hours}h ${remainder}m"
}

@Composable
private fun ReadingsContent(
    state: CompanionState,
    onReading: (ReadingItem) -> Unit,
    onFacultyProfile: (FacultyProfile) -> Unit,
    onToggleDone: (String) -> Unit,
) {
    var expandedCourseId by rememberSaveable { mutableStateOf<String?>(null) }
    var initialCourseChosen by rememberSaveable { mutableStateOf(false) }
    val expandedSections = remember { mutableStateListOf<String>() }
    val readingsByCourse = state.readings.groupBy { it.catId }
    val profilesByCourse = state.facultyProfiles.groupBy { it.courseCatId }
    val courseIds = (readingsByCourse.keys + profilesByCourse.keys).distinct()
    val initialScheduleReady = state.scheduleSync.lastSuccess != null || state.scheduleSync.error != null
    LaunchedEffect(courseIds, state.scheduleSessions, initialScheduleReady) {
        if (!initialCourseChosen && courseIds.isNotEmpty() && initialScheduleReady) {
            expandedCourseId = defaultReadingCourseId(state.readings, state.scheduleSessions, Instant.now())
            initialCourseChosen = true
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.readingSync.inProgress && courseIds.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Teal) } }
        } else if (courseIds.isEmpty()) {
            item { EmptyState("No course content yet", "The LMS listing has not produced readings or faculty profiles.") }
        } else {
            items(courseIds, key = { it }) { courseId ->
                val readings = readingsByCourse[courseId].orEmpty()
                val profiles = profilesByCourse[courseId].orEmpty()
                val courseName = readings.firstOrNull()?.courseName
                    ?: profiles.firstOrNull()?.courseName
                    ?: "Course"
                val index = courseIds.indexOf(courseId)
                CourseReadingCard(
                    courseId = courseId,
                    courseName = courseName,
                    readings = readings,
                    facultyProfiles = profiles,
                    sessions = state.scheduleSessions,
                    now = state.now,
                    design = index % 4,
                    expanded = courseId == expandedCourseId,
                    expandedSections = expandedSections,
                    onToggleCourse = {
                        expandedCourseId = if (expandedCourseId == courseId) null else courseId
                    },
                    onToggleSection = { id ->
                        if (id in expandedSections) expandedSections.remove(id) else expandedSections.add(id)
                    },
                    onReading = onReading,
                    onFacultyProfile = onFacultyProfile,
                    onToggleDone = onToggleDone,
                )
            }
        }
        state.readingSync.error?.let { error -> item { InlineError("Readings may be stale. ${errorText(error)}") } }
    }
}

@Composable
private fun CourseReadingCard(
    courseId: String,
    courseName: String,
    readings: List<ReadingItem>,
    facultyProfiles: List<FacultyProfile>,
    sessions: List<Session>,
    now: java.time.Instant,
    design: Int,
    expanded: Boolean,
    expandedSections: List<String>,
    onToggleCourse: () -> Unit,
    onToggleSection: (String) -> Unit,
    onReading: (ReadingItem) -> Unit,
    onFacultyProfile: (FacultyProfile) -> Unit,
    onToggleDone: (String) -> Unit,
) {
    val colors = COURSE_COLORS[design]
    val pending = readings.count { !it.done }
    val mandatory = readings.count { it.mandatory && !it.done }
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().background(colors.panel).clickable(onClick = onToggleCourse).padding(16.dp),
            ) {
                Column(Modifier.fillMaxWidth(.82f)) {
                    Text("COURSE", color = colors.accent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .6.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(courseName, color = Ink, fontSize = 19.sp, lineHeight = 21.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(9.dp))
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(if (readings.isEmpty()) "No readings posted" else "$pending to read")
                            }
                            if (mandatory > 0) append("  ·  $mandatory mandatory")
                            if (facultyProfiles.isNotEmpty()) {
                                append("  ·  ${facultyProfiles.size} faculty ${if (facultyProfiles.size == 1) "profile" else "profiles"}")
                            }
                        },
                        color = Ink.copy(alpha = .72f),
                        fontSize = 11.sp,
                    )
                    readings.firstOrNull { !it.done }?.let { preview ->
                        Spacer(Modifier.height(5.dp))
                        Text("Next: ${preview.title}", color = Ink.copy(alpha = .62f), fontSize = 10.sp, maxLines = 1)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(if (expanded) "⌃  Collapse" else "⌄  Open", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                }
                CourseArtwork(design, colors.shape, Modifier.align(Alignment.BottomEnd).size(110.dp, 88.dp))
            }
            AnimatedVisibility(visible = expanded, enter = fadeIn() + slideInVertically { -it / 8 }, exit = fadeOut()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (facultyProfiles.isNotEmpty()) {
                        Text(
                            "FACULTY PROFILE${if (facultyProfiles.size == 1) "" else "S"}",
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 12.dp),
                            fontSize = 11.sp,
                            color = colors.accent,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = .5.sp,
                        )
                        facultyProfiles.forEach { profile ->
                            FacultyProfileRow(profile, colors) { onFacultyProfile(profile) }
                        }
                        if (readings.isNotEmpty()) HorizontalDivider(color = Line)
                    }
                    val plan = planCourseReadings(readings, sessions, now)
                    plan.nextSession?.let { next ->
                        Text(
                            "PREPARE FOR SESSION ${next.sessionNumber}  ·  ${next.readings.size}",
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 12.dp),
                            fontSize = 11.sp,
                            color = colors.accent,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = .5.sp,
                        )
                        next.readings.forEach { reading -> ReadingRow(reading, colors, onReading, onToggleDone) }
                        HorizontalDivider(color = Line)
                    }
                    val secondary = buildList {
                        if (plan.upcomingSessions.isNotEmpty()) {
                            add(
                                Triple(
                                    "upcoming",
                                    "More upcoming readings · ${plan.upcomingSessions.sumOf { it.readings.size }}",
                                    plan.upcomingSessions.flatMap { it.readings },
                                ),
                            )
                        }
                        if (plan.general.isNotEmpty()) add(Triple("general", "General readings · ${plan.general.size}", plan.general))
                    }
                    secondary.forEach { (kind, label, sectionReadings) ->
                        val sectionId = "$courseId:$kind"
                        val open = sectionId in expandedSections
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onToggleSection(sectionId) }.padding(vertical = 12.dp, horizontal = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, fontSize = 12.sp, color = Ink, fontWeight = FontWeight.Bold)
                            Text(if (open) "−" else "+", fontSize = 18.sp, color = colors.accent)
                        }
                        AnimatedVisibility(open) {
                            Column {
                                sectionReadings.forEach { reading -> ReadingRow(reading, colors, onReading, onToggleDone) }
                            }
                        }
                        HorizontalDivider(color = Line)
                    }
                }
            }
        }
    }
}

@Composable
private fun FacultyProfileRow(
    profile: FacultyProfile,
    colors: CourseColors,
    onOpen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen)
            .padding(vertical = 11.dp, horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(colors.panel, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                profile.displayName.trim().firstOrNull()?.uppercase() ?: "F",
                color = colors.accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.displayName, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(profile.roleTitle ?: "Open LMS faculty profile", color = Muted, fontSize = 10.sp)
        }
        androidx.compose.material3.Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = "Open ${profile.displayName}'s faculty profile",
            tint = colors.accent,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ReadingRow(
    reading: ReadingItem,
    colors: CourseColors,
    onReading: (ReadingItem) -> Unit,
    onToggleDone: (String) -> Unit,
) {
    val view = LocalView.current
    val rowBackground by animateColorAsState(
        if (reading.done) DonePurpleSoft else Color.Transparent,
        label = "reading done",
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(rowBackground)
            .clickable { onReading(reading) }.padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(reading.title, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (reading.mandatory) {
                    Text("Mandatory", Modifier.background(colors.panel, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 3.dp), color = colors.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                if (reading.done) {
                    Text("Done", Modifier.background(DonePurple, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 3.dp), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Text(reading.progress.label, color = Muted, fontSize = 10.sp)
            }
        }
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(if (reading.done) DonePurple else Color.White)
                .border(1.5.dp, if (reading.done) DonePurple else Muted.copy(alpha = .55f), CircleShape)
                .clickable {
                    performReadingHaptic(view)
                    onToggleDone(reading.vid)
                },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(Icons.Default.Check, contentDescription = if (reading.done) "Undo Done" else "Mark Done", tint = if (reading.done) Color.White else Color.Transparent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CourseArtwork(design: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        when (design) {
            0 -> {
                drawCircle(color.copy(alpha = .43f), size.minDimension * .30f, Offset(size.width * .44f, size.height * .78f))
                drawCircle(color.copy(alpha = .43f), size.minDimension * .30f, Offset(size.width * .80f, size.height * .56f))
            }
            1 -> drawArc(color.copy(alpha = .38f), 190f, 175f, false, Offset(size.width * .22f, size.height * .12f), Size(size.width, size.height * 1.35f), style = Stroke(15.dp.toPx(), cap = StrokeCap.Round))
            2 -> repeat(3) { line -> drawLine(color.copy(alpha = .38f), Offset(size.width * .18f + line * 11.dp.toPx(), size.height * (.35f + line * .16f)), Offset(size.width, size.height * (.35f + line * .16f)), 8.dp.toPx(), StrokeCap.Round) }
            else -> drawCircle(color.copy(alpha = .28f), size.minDimension * .48f, Offset(size.width * .68f, size.height * .56f), style = Stroke(14.dp.toPx()))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LectureSheet(session: Session, state: CompanionState, onDismiss: () -> Unit, onMark: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column {
            Box(Modifier.fillMaxWidth().background(Blush).padding(24.dp)) {
                Column(Modifier.fillMaxWidth(.82f)) {
                    Text("TERM 1 · ${session.state.label}", color = BlushAccent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(8.dp))
                    Text(session.name, fontSize = 27.sp, lineHeight = 29.sp, fontWeight = FontWeight.ExtraBold, color = BlushInk)
                    Spacer(Modifier.height(16.dp))
                    Text("${TIME_FORMAT.format(session.start.atZone(IST))}–${TIME_FORMAT.format(session.end.atZone(IST))}", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = BlushInk)
                    Spacer(Modifier.height(6.dp))
                    Text(session.placeLine(), color = BlushInk.copy(alpha = .68f), fontWeight = FontWeight.SemiBold)
                }
                CourseArtwork(0, BlushShape, Modifier.align(Alignment.BottomEnd).size(110.dp, 88.dp))
            }
            Column(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
                DetailRow("Trainer", session.trainer ?: "Not posted")
                DetailRow("Attendance", session.state.label)
                DetailRow("Last updated", state.sync.lastSuccess?.let { TIME_FORMAT.format(it.atZone(IST)) } ?: "Cached")
                AnimatedVisibility(session.state == SessionState.OPEN && session.nid != null) {
                    Button(onClick = { session.nid?.let(onMark) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text("Mark attendance") }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(vertical = 10.dp)) { Text("Close", color = Ink, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingSheet(
    reading: ReadingItem,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onDownload: (ReadingItem) -> Unit,
    onToggleDone: (String) -> Unit,
) {
    val view = LocalView.current
    val design = reading.catId.hashCode().mod(4)
    val colors = COURSE_COLORS[design]
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column {
            Box(Modifier.fillMaxWidth().background(colors.panel).padding(24.dp)) {
                Column(Modifier.fillMaxWidth(.88f)) {
                    Text(if (reading.mandatory) "MANDATORY READING" else reading.sectionName.uppercase(), color = colors.accent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(8.dp))
                    Text(reading.title, color = Ink, fontSize = 25.sp, lineHeight = 27.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(8.dp))
                    Text(reading.courseName, color = Ink.copy(alpha = .68f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                CourseArtwork(design, colors.shape, Modifier.align(Alignment.BottomEnd).size(105.dp, 82.dp))
            }
            Column(Modifier.padding(20.dp)) {
                Text(
                    "This opens the authenticated LMS item only when you choose it. Background sync never opens reading pages.",
                    color = Muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Button(onClick = { onOpen(reading.sourceUrl) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Ink)) {
                        androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Open LMS")
                    }
                    Button(onClick = { onDownload(reading) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Soft)) {
                        androidx.compose.material3.Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp), tint = Ink)
                        Spacer(Modifier.width(7.dp))
                        Text("Download", color = Ink)
                    }
                }
                Button(onClick = {
                    performReadingHaptic(view)
                    onToggleDone(reading.vid)
                }, modifier = Modifier.fillMaxWidth().padding(top = 9.dp), colors = ButtonDefaults.buttonColors(containerColor = if (reading.done) Soft else Teal)) {
                    Text(if (reading.done) "Undo Done" else "Mark Done", color = if (reading.done) Ink else Color.White)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) { Text("Close reading", color = Muted) }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted)
        Text(value, color = Ink, fontWeight = FontWeight.Bold)
    }
    HorizontalDivider(color = Line)
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).background(Mint, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) { Text("✓", color = Teal, fontSize = 24.sp) }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(7.dp))
        Text(body, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun InlineError(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(14.dp).background(Butter, RoundedCornerShape(14.dp)).padding(13.dp), color = Ink, fontSize = 12.sp)
}

@Composable
private fun BetaTourContent(onFinish: () -> Unit, modifier: Modifier = Modifier) {
    var page by rememberSaveable { mutableStateOf(0) }
    val pages = listOf(
        "Welcome" to "Companion keeps your schedule, attendance and readings together. This short tour appears once for this app experience.",
        "Automatic attendance" to "Precise location proves you are on campus. Allow all the time lets Android check during class. Precise alarms start the check on time.",
        "Alerts and privacy" to "Notifications tell you when a class opens and whether marking worked. They are helpful but optional. Your LMS password stays on this phone.",
    )
    Column(modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("${page + 1} of ${pages.size}", color = Teal, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        AnimatedContent(
            targetState = page,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "tour page",
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) { shownPage ->
            val (title, body) = pages[shownPage]
            Column {
                Text(title, color = Ink, fontSize = 31.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(12.dp))
                Text(body, color = Muted, fontSize = 16.sp, lineHeight = 24.sp)
            }
        }
        Spacer(Modifier.height(30.dp))
        Button(
            onClick = { if (page == pages.lastIndex) onFinish() else page += 1 },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TealDeep),
        ) { Text(if (page == pages.lastIndex) "Continue" else "Next", fontWeight = FontWeight.Bold) }
        if (page > 0) {
            TextButton(onClick = { page -= 1 }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Back") }
        }
        TextButton(onClick = onFinish, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Skip tour") }
    }
}

@Composable
private fun AutoAttendanceEducation(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.Center) {
        item {
            Text("Enable automatic attendance", fontSize = 29.sp, lineHeight = 32.sp, color = Ink, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(12.dp))
            Text("Android will ask in this order:", color = Muted, fontSize = 15.sp)
            Spacer(Modifier.height(18.dp))
            PermissionExplanation("1", "Precise location", "Needed for both manual and automatic attendance inside the campus zone.")
            PermissionExplanation("2", "Allow all the time", "Lets the phone check automatically while the app is closed.")
            PermissionExplanation("3", "Precise alarms", "Lets the check start at the class time.")
            PermissionExplanation("4", "Notifications", "Optional alerts for attendance windows and results.")
            Spacer(Modifier.height(18.dp))
            Text("If a required permission is refused or later removed, automatic attendance stops and shows Needs attention. Manual features remain available.", color = Ink, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = TealDeep)) {
                Text("Continue and enable", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PermissionExplanation(number: String, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(30.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) { Text(number, color = Teal, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.ExtraBold)
            Text(body, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun BetaProfileContent(
    initialName: String,
    initialSection: String,
    initialGroup: String,
    detectedSections: Set<String>,
    detectedGroups: Set<String>,
    supportNameRequired: Boolean,
    onSave: (String, String?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var section by rememberSaveable(initialSection) { mutableStateOf(initialSection) }
    var group by rememberSaveable(initialGroup) { mutableStateOf(initialGroup) }
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Confirm your beta profile", color = Ink, fontSize = 28.sp, lineHeight = 31.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text("The LMS detected ${detectedCohortSummary(detectedSections, detectedGroups)}.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(name, { name = it }, label = { Text("Name for beta support") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(section, { section = it }, label = { Text("Section") }, placeholder = { Text("Section A") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(group, { group = it }, label = { Text("PLC or Group") }, placeholder = { Text("Group 4") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text("Corrections help the beta owner support you. They never override the LMS schedule or decide attendance.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { onSave(name.trim(), section.trim().takeIf(String::isNotEmpty), group.trim().takeIf(String::isNotEmpty)) },
            enabled = section.isNotBlank() && (!supportNameRequired || name.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TealDeep),
        ) { Text("Confirm profile", fontWeight = FontWeight.Bold) }
        if (detectedSections.isEmpty()) Text("Automatic attendance needs a Section detected from your LMS schedule. You can still use manual features.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ProfileContent(
    state: CompanionState,
    profile: BetaProfile?,
    autoAttendanceEnabled: Boolean,
    setupStatus: BetaSetupStatus,
    detectedSections: Set<String>,
    detectedGroups: Set<String>,
    onAutoAttendance: (Boolean) -> Unit,
    onSaveProfile: (String, String?, String?) -> Unit,
    onDeleteName: () -> Unit,
    onReopenTour: () -> Unit,
    onRefreshAttendance: () -> Unit,
    onShowReleaseNotes: () -> Unit,
    onSignOut: () -> Unit,
) {
    var name by rememberSaveable(profile?.supportName) { mutableStateOf(profile?.supportName.orEmpty()) }
    var section by rememberSaveable(profile?.selfSection) { mutableStateOf(profile?.selfSection.orEmpty()) }
    var group by rememberSaveable(profile?.selfPlc) { mutableStateOf(profile?.selfPlc.orEmpty()) }
    var editingProfile by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().background(Paper),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ProfileHeader(
                name = profile?.supportName.orEmpty(),
                section = profile?.selfSection,
                group = profile?.selfPlc,
            )
        }
        item {
            AttendanceOverviewCard(
                state = state,
                onRefresh = onRefreshAttendance,
            )
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, Line),
            ) {
                Column(Modifier.padding(18.dp)) {
                val statusLabel = when {
                    !setupStatus.canEnableAutomaticAttendance -> "Needs attention"
                    autoAttendanceEnabled -> "Ready"
                    else -> "Off"
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(42.dp).background(Mint, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text("✓", color = Teal, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Automatic attendance", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                        Text(statusLabel, color = if (statusLabel == "Ready") Green else BlushAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Switch(
                        checked = autoAttendanceEnabled,
                        onCheckedChange = { onAutoAttendance(it) },
                        enabled = setupStatus.canEnableAutomaticAttendance || autoAttendanceEnabled,
                        colors = SwitchDefaults.colors(checkedTrackColor = Teal),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (setupStatus.canEnableAutomaticAttendance) {
                        "Companion can check your campus location near class time and mark attendance when the LMS allows it."
                    } else {
                        "Precise location, Allow all the time and precise alarms are required before automatic attendance can run."
                    },
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                if (!setupStatus.notificationsAvailable) {
                    Text("Notifications are optional and currently off.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, Line),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Personal details", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                            Text("LMS detected ${detectedCohortSummary(detectedSections, detectedGroups)}", color = Muted, fontSize = 11.sp)
                        }
                        if (!editingProfile) {
                            TextButton(onClick = { editingProfile = true }) { Text("Edit", color = Teal, fontWeight = FontWeight.Bold) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (editingProfile) {
                        OutlinedTextField(name, { name = it }, label = { Text("Support name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(section, { section = it }, label = { Text("Section") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(group, { group = it }, label = { Text("PLC or Group") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                name = profile?.supportName.orEmpty()
                                section = profile?.selfSection.orEmpty()
                                group = profile?.selfPlc.orEmpty()
                                editingProfile = false
                            }, modifier = Modifier.weight(1f)) { Text("Cancel", color = Muted) }
                            Button(
                                onClick = {
                                    onSaveProfile(name.trim(), section.trim(), group.trim())
                                    editingProfile = false
                                },
                                enabled = section.isNotBlank(),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = TealDeep),
                            ) { Text("Save") }
                        }
                        if (!profile?.supportName.isNullOrBlank()) {
                            TextButton(onClick = onDeleteName, modifier = Modifier.fillMaxWidth()) {
                                Text("Delete my support name", color = BlushAccent)
                            }
                        }
                    } else {
                        ProfileDetailRow("Name", profile?.supportName ?: "Not added")
                        ProfileDetailRow("Section", profile?.selfSection ?: "Not added")
                        ProfileDetailRow("PLC / Group", profile?.selfPlc ?: "Not added", divider = false)
                    }
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, Line),
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    ProfileAction("What’s new", onShowReleaseNotes)
                    HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 10.dp))
                    ProfileAction("View tour again", onReopenTour)
                    HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 10.dp))
                    ProfileAction("Sign out", onSignOut, color = Muted)
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(name: String, section: String?, group: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(64.dp).background(TealDeep, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(profileInitials(name), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Profile", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(name.ifBlank { "ISDM student" }, color = Ink, fontSize = 23.sp, lineHeight = 27.sp, fontWeight = FontWeight.ExtraBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                section?.takeIf(String::isNotBlank)?.let { ProfileChip(it) }
                group?.takeIf(String::isNotBlank)?.let { ProfileChip(it) }
            }
        }
    }
}

private fun profileInitials(name: String): String = name.trim()
    .split(Regex("\\s+"))
    .filter(String::isNotBlank)
    .take(2)
    .joinToString("") { it.first().uppercaseChar().toString() }
    .ifBlank { "IS" }

@Composable
private fun ProfileChip(label: String) {
    Text(
        label,
        color = TealDeep,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(Mint, RoundedCornerShape(20.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun AttendanceOverviewCard(state: CompanionState, onRefresh: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Attendance", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Last 1 year · completed sessions", color = Muted, fontSize = 11.sp)
                }
                Text(
                    "Updated from LMS",
                    color = Green,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(DoneSoft, RoundedCornerShape(20.dp)).padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            val summary = state.attendanceSummary
            when {
                summary != null -> {
                    val percentage = summary.presentPercentage.stripTrailingZeros().toPlainString()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
                            Canvas(
                                Modifier.fillMaxSize().semantics {
                                    contentDescription = "$percentage percent attendance"
                                },
                            ) {
                                val stroke = 12.dp.toPx()
                                val inset = stroke / 2f
                                drawArc(
                                    color = Soft,
                                    startAngle = -90f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    topLeft = Offset(inset, inset),
                                    size = Size(size.width - stroke, size.height - stroke),
                                    style = Stroke(stroke, cap = StrokeCap.Round),
                                )
                                drawArc(
                                    color = Teal,
                                    startAngle = -90f,
                                    sweepAngle = 360f * summary.presentPercentage.toFloat().div(100f).coerceIn(0f, 1f),
                                    useCenter = false,
                                    topLeft = Offset(inset, inset),
                                    size = Size(size.width - stroke, size.height - stroke),
                                    style = Stroke(stroke, cap = StrokeCap.Round),
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$percentage%", color = TealDeep, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                                Text("attendance", color = Muted, fontSize = 10.sp)
                            }
                        }
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${summary.present} present", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                            Text("${summary.absent} absent", color = BlushAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(5.dp))
                            Text("${summary.total} completed sessions", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                            if (summary.notMarked > 0) {
                                Text("${summary.notMarked} not marked", color = Muted, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AttendanceStat("Present", summary.present.toString(), DoneSoft, Green, Modifier.weight(1f))
                        AttendanceStat("Absent", summary.absent.toString(), Blush.copy(alpha = 0.48f), BlushAccent, Modifier.weight(1f))
                        AttendanceStat("Total", summary.total.toString(), Mint, TealDeep, Modifier.weight(1f))
                    }
                }
                state.attendanceSync.inProgress -> {
                    Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Teal, strokeWidth = 3.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Updating from the LMS…", color = Muted)
                    }
                }
                else -> Text("Attendance is unavailable right now.", color = Muted, modifier = Modifier.padding(vertical = 24.dp))
            }
            TextButton(
                onClick = onRefresh,
                enabled = !state.attendanceSync.inProgress,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text(if (state.attendanceSync.inProgress) "Updating…" else "Update from LMS", color = Teal, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun AttendanceStat(label: String, value: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.background(background, RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = foreground, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = foreground, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ProfileDetailRow(label: String, value: String, divider: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    if (divider) HorizontalDivider(color = Line)
}

@Composable
private fun ProfileAction(label: String, onClick: () -> Unit, color: Color = Ink) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = color, fontWeight = FontWeight.Bold)
        Text("›", color = Muted, fontSize = 20.sp)
    }
}

private fun detectedProfileCohorts(state: CompanionState): Pair<Set<String>, Set<String>> {
    val sections = state.detectedCohorts.sections.toCollection(linkedSetOf())
    val groups = state.detectedCohorts.groups.toCollection(linkedSetOf())
    return sections to groups
}

private fun detectedCohortSummary(sections: Set<String>, groups: Set<String>): String {
    val labels = sections.map { "Section $it" } + groups.map { "Group $it" }
    return labels.ifEmpty { listOf("no Section or Group") }.joinToString()
}

private fun profileDetectionChanged(
    profile: BetaProfile?,
    detected: Pair<Set<String>, Set<String>>,
    detectionLoaded: Boolean,
): Boolean {
    if (profile == null || !detectionLoaded) return false
    return profile.detectedSections != detected.first || profile.detectedGroups != detected.second
}

@Composable
private fun BetaEnrollmentContent(
    enrolling: Boolean,
    detectedName: String,
    detectedSections: Set<String>,
    detectedGroups: Set<String>,
    onEnroll: (String, String, String, String?, Set<String>, Set<String>, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var supportName by rememberSaveable(detectedName) { mutableStateOf(detectedName) }
    var section by rememberSaveable(detectedSections) { mutableStateOf(detectedSections.firstOrNull()?.let { "Section $it" }.orEmpty()) }
    var plc by rememberSaveable(detectedGroups) { mutableStateOf(detectedGroups.firstOrNull()?.let { "Group $it" }.orEmpty()) }
    var consented by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val valid = isBetaEnrollmentValid(inviteCode, section, consented) && supportName.isNotBlank()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        item {
            Box(Modifier.size(14.dp).background(ButterStrong, RoundedCornerShape(5.dp)))
            Spacer(Modifier.height(18.dp))
            Text("Join the one-week beta", fontSize = 30.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            Spacer(Modifier.height(8.dp))
            Text("Your LMS sign-in is ready. Confirm the detected details below.", color = Muted, lineHeight = 21.sp)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = inviteCode,
                onValueChange = { inviteCode = it.uppercase(Locale.ROOT) },
                label = { Text("Invite code") },
                singleLine = true,
                enabled = !enrolling,
                isError = attempted && inviteCode.isBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = supportName,
                onValueChange = { supportName = it },
                label = { Text("Name for beta support") },
                singleLine = true,
                enabled = !enrolling,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = section,
                onValueChange = { section = it },
                label = { Text("Section") },
                placeholder = { Text("e.g. Section A") },
                singleLine = true,
                enabled = !enrolling,
                isError = attempted && section.isBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = plc,
                onValueChange = { plc = it },
                label = { Text("PLC or group (optional)") },
                placeholder = { Text("e.g. PLC 4") },
                singleLine = true,
                enabled = !enrolling,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(18.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)
                    .border(1.dp, Line, RoundedCornerShape(16.dp)).padding(16.dp),
            ) {
                Text("What this beta sends", color = Ink, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                Text("• App events, device model, section/PLC, schedule feedback and attendance outcomes.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Text("• Your support name, visible only to the beta owner and deleted 30 days after the beta ends.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Text("• Exact location only when an attendance decision is made.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Text("• Screenshots only when you select and send them.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Text("• Your LMS password and reusable cookies stay on this phone.", color = Ink, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
            }
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !enrolling) { consented = !consented }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = consented, onCheckedChange = { consented = it }, enabled = !enrolling)
                Text("I understand and agree to this one-week beta data collection.", color = Ink, fontSize = 12.sp, lineHeight = 17.sp)
            }
            if (attempted && !valid) {
                Text("Invite code, name, Section, and consent are required.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = {
                    attempted = true
                    if (valid) onEnroll(inviteCode.trim(), supportName.trim(), section.trim(), plc.trim().takeIf(String::isNotEmpty), detectedSections, detectedGroups, consented)
                },
                enabled = !enrolling,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TealDeep),
            ) {
                if (enrolling) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("Join beta", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LoginContent(state: CompanionState, onSignIn: (String, String) -> Unit, modifier: Modifier = Modifier) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(14.dp).background(ButterStrong, RoundedCornerShape(5.dp)))
        Spacer(Modifier.height(18.dp))
        Text("Your ISDM companion", fontSize = 30.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text("Schedule, attendance and course readings—calmly in one place.", color = Muted, lineHeight = 21.sp)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(email, { email = it }, label = { Text("LMS email") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, { password = it }, label = { Text("LMS password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        state.error?.let { Spacer(Modifier.height(12.dp)); Text(errorText(it), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        Spacer(Modifier.height(20.dp))
        Button(onClick = { onSignIn(email, password) }, enabled = !state.sync.inProgress, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = TealDeep)) {
            if (state.sync.inProgress) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Text("Sign in", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Text("Your login is encrypted on this phone and sent only to the ISDM LMS.", color = Muted, fontSize = 11.sp)
    }
}

private fun Session.metaLine(): String = listOfNotNull(room, floorLabel, trainer).joinToString(" · ").ifBlank { "Details not posted" }
private fun Session.placeLine(): String = listOfNotNull(room, floorLabel).joinToString(" · ").ifBlank { "Location not posted" }

private fun performAttendanceHaptic(view: View, success: Boolean) {
    val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (success) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT
    } else if (success) {
        HapticFeedbackConstants.LONG_PRESS
    } else {
        HapticFeedbackConstants.KEYBOARD_TAP
    }
    view.performHapticFeedback(feedback)
}

private fun performReadingHaptic(view: View) {
    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

private val SessionState.label: String
    get() = when (this) {
        SessionState.MARKED -> "Present"
        SessionState.OPEN -> "Attendance open"
        SessionState.UPCOMING -> "Upcoming session"
        SessionState.MISSED -> "Ended"
        SessionState.NO_ATTENDANCE -> "No attendance"
        SessionState.DONE -> "Earlier session"
    }

private val LmsReadingProgress.label: String
    get() = when (this) {
        LmsReadingProgress.UNOPENED -> "Not opened on LMS"
        LmsReadingProgress.VIEWED -> "Viewed on LMS"
        LmsReadingProgress.IN_PROGRESS -> "In progress on LMS"
        LmsReadingProgress.COMPLETED -> "Completed on LMS"
        LmsReadingProgress.UNKNOWN -> "LMS status unavailable"
    }

private fun errorText(error: EngineError): String = when (error) {
    EngineError.CredentialsMissing -> "Enter your LMS login to continue."
    is EngineError.InvalidCommand -> error.message
    is EngineError.AuthenticationFailed -> error.message
    is EngineError.NetworkFailure -> "The LMS could not be reached. Check your connection and retry."
    is EngineError.LmsFailure -> error.message
    is EngineError.MarkWindowClosed -> "The LMS marking window is no longer open."
    is EngineError.AttendanceLocationDenied -> when (error.reason) {
        LocationGateReason.MISSING_EVIDENCE -> "Attendance is locked because a current location was not available."
        LocationGateReason.STALE_EVIDENCE -> "Attendance is locked because the phone location is not recent."
        LocationGateReason.INACCURATE_EVIDENCE -> "Attendance is locked because the phone location is not precise enough."
        LocationGateReason.MOCK_LOCATION_EVIDENCE -> "Attendance is locked while a mock location is active."
        LocationGateReason.OUTSIDE_CAMPUS_ZONE -> "Attendance is available only inside the ISDM Campus Zone."
    }
    is EngineError.MarkRejected -> error.message
    EngineError.SystemLimitReached -> "Android’s monitoring time limit was reached. Open the app to restart."
}

@Composable
private fun companionColors() = androidx.compose.material3.lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    background = Paper,
    surface = Color.White,
    surfaceVariant = Soft,
    error = Color(0xFFB42318),
)

private data class CourseColors(val panel: Color, val accent: Color, val shape: Color)
private val COURSE_COLORS = listOf(
    CourseColors(Color(0xFFE8BFDD), Color(0xFF8F1B3A), Color(0xFFBD82AA)),
    CourseColors(Color(0xFFD8EBE3), Color(0xFF17665E), Color(0xFF8FBEB1)),
    CourseColors(Color(0xFFF4DF9F), Color(0xFF8A5B0E), Color(0xFFE0AE4D)),
    CourseColors(Color(0xFFD9E7F2), Color(0xFF315F7D), Color(0xFF8DB7D0)),
)

private val Ink = Color(0xFF15201F)
private val Paper = Color(0xFFF4F8F7)
private val Teal = Color(0xFF0D5F5C)
private val TealDeep = Color(0xFF073B3A)
private val Muted = Color(0xFF65706D)
private val Line = Color(0xFFDCE5E2)
private val Soft = Color(0xFFE4ECE9)
private val Green = Color(0xFF28755A)
private val DoneSoft = Color(0xFFDFF1E8)
private val DonePurple = Color(0xFF7151A1)
private val DonePurpleSoft = Color(0xFFF0EAF8)
private val Blush = Color(0xFFE8BFDD)
private val BlushInk = Color(0xFF2C2630)
private val BlushAccent = Color(0xFFB45558)
private val BlushShape = Color(0xFFBD82AA)
private val Mint = Color(0xFFD8EBE3)
private val Butter = Color(0xFFF4DF9F)
private val ButterStrong = Color(0xFFE0AE4D)
private val SkyAccent = Color(0xFF315F7D)
private val IST = ZoneId.of("Asia/Kolkata")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val TIME_SHORT = DateTimeFormatter.ofPattern("H:mm", Locale.ENGLISH)
private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.ENGLISH)

internal val companionTypography = Typography().run {
    val family = FontFamily(
        Font(R.font.roboto_variable, FontWeight.Normal),
        Font(R.font.roboto_variable, FontWeight.Medium),
        Font(R.font.roboto_variable, FontWeight.SemiBold),
        Font(R.font.roboto_variable, FontWeight.Bold),
        Font(R.font.roboto_variable, FontWeight.ExtraBold),
    )
    copy(
        displayLarge = displayLarge.copy(fontFamily = family),
        displayMedium = displayMedium.copy(fontFamily = family),
        displaySmall = displaySmall.copy(fontFamily = family),
        headlineLarge = headlineLarge.copy(fontFamily = family),
        headlineMedium = headlineMedium.copy(fontFamily = family),
        headlineSmall = headlineSmall.copy(fontFamily = family),
        titleLarge = titleLarge.copy(fontFamily = family),
        titleMedium = titleMedium.copy(fontFamily = family),
        titleSmall = titleSmall.copy(fontFamily = family),
        bodyLarge = bodyLarge.copy(fontFamily = family),
        bodyMedium = bodyMedium.copy(fontFamily = family),
        bodySmall = bodySmall.copy(fontFamily = family),
        labelLarge = labelLarge.copy(fontFamily = family),
        labelMedium = labelMedium.copy(fontFamily = family),
        labelSmall = labelSmall.copy(fontFamily = family),
    )
}
