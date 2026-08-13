package org.isdm.companion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import org.isdm.companion.domain.LocationGateReason
import java.time.Duration
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
            viewModel.setAutoAttendance(granted)
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
                viewModel.setAutoAttendance(true)
            }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    onShareIssue = { description, images ->
                        startActivity(
                            Intent.createChooser(
                                createIssueReportShareIntent(description, images),
                                "Share issue report",
                            ),
                        )
                        diagnostics.log(
                            "issue_report_shared",
                            mapOf("attachment_count" to images.size.toString()),
                        )
                    },
                )
            }
        }
        handleIntent(intent)
        val autoAttendanceEnabled = (application as CompanionApplication).autoAttendanceStore.isEnabled()
        if (savedInstanceState == null && autoAttendanceEnabled && pendingMarkSessionId == null) {
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

    private fun handleIntent(intent: Intent?) {
        val nid = intent?.getStringExtra(EXTRA_MARK_NID) ?: return
        intent.removeExtra(EXTRA_MARK_NID)
        requestMark(nid, fromIntent = true)
    }

    private fun requestAutoAttendance(enabled: Boolean) {
        if (!enabled) {
            pendingAutoAttendance = false
            viewModel.setAutoAttendance(false)
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
            viewModel.setAutoAttendance(true)
            viewModel.showMessage("Auto attendance is on but locked until Location is set to Allow all the time.")
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
        }
    }

    private fun hasPreciseLocationAccess(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocationAccess(): Boolean =
        hasPreciseLocationAccess() && (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            )

    companion object {
        const val EXTRA_MARK_NID = "mark_nid"
    }
}

private enum class Destination { SCHEDULE, READINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionScreen(
    viewModel: CompanionViewModel,
    onAutoAttendance: (Boolean) -> Unit,
    onMarkAttendance: (String) -> Unit,
    onOpenReading: (String) -> Unit,
    onShareIssue: (String, List<android.net.Uri>) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val initializing by viewModel.initializing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val autoAttendanceEnabled by viewModel.autoAttendanceEnabled.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var destination by rememberSaveable { mutableStateOf(Destination.SCHEDULE) }
    var selectedDate by rememberSaveable { mutableStateOf(state.today) }
    var selectedSession by remember { mutableStateOf<Session?>(null) }
    var selectedReading by remember { mutableStateOf<ReadingItem?>(null) }
    var issueReportOpen by rememberSaveable { mutableStateOf(false) }

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

    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!initializing && state.identity != null) {
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
            initializing -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = Teal) }

            state.identity == null -> LoginContent(
                state = state,
                onSignIn = viewModel::signIn,
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
                        )
                    } else {
                        ReadingsContent(
                            state = state,
                            onReading = { selectedReading = it },
                            onFacultyProfile = { onOpenReading(it.sourceUrl) },
                            onToggleDone = viewModel::toggleReadingDone,
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
            onShare = { description, images ->
                issueReportOpen = false
                onShareIssue(description, images)
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
        val syncing = state.sync.inProgress || state.scheduleSync.inProgress || state.readingSync.inProgress
        val latest = listOfNotNull(state.sync.lastSuccess, state.scheduleSync.lastSuccess, state.readingSync.lastSuccess).maxOrNull()
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
                    if (item == Destination.SCHEDULE) "Schedule" else "Readings",
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
) {
    val source = state.scheduleSessions.ifEmpty { state.sessions }
    val sessions = source.filter { it.start.atZone(IST).toLocalDate() == selectedDate }
    val focus = sessions.firstOrNull { it.state == SessionState.OPEN }
        ?: sessions.firstOrNull { it.state == SessionState.UPCOMING }

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
                            focus = session === focus,
                            now = state.now,
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
    focus: Boolean,
    now: java.time.Instant,
    onOpen: () -> Unit,
    onMark: (String) -> Unit,
) {
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
                            withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(session.name) }
                            if (session.state == SessionState.UPCOMING) {
                                append(" starts in ")
                                withStyle(SpanStyle(color = BlushAccent, fontWeight = FontWeight.ExtraBold)) { append(formatSessionCountdown(minutes)) }
                                append(".")
                            } else {
                                append(" is in session.")
                            }
                        },
                        fontSize = 18.sp,
                        lineHeight = 20.sp,
                        color = BlushInk,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(session.metaLine(), fontSize = 11.sp, color = BlushInk.copy(alpha = .66f), fontWeight = FontWeight.SemiBold)
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
    val expandedCourses = remember { mutableStateListOf<String>() }
    val expandedSections = remember { mutableStateListOf<String>() }
    val readingsByCourse = state.readings.groupBy { it.catId }
    val profilesByCourse = state.facultyProfiles.groupBy { it.courseCatId }
    val courseIds = (readingsByCourse.keys + profilesByCourse.keys).distinct()
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
                    expanded = courseId in expandedCourses,
                    expandedSections = expandedSections,
                    onToggleCourse = {
                        if (courseId in expandedCourses) expandedCourses.remove(courseId) else expandedCourses.add(courseId)
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
                    Spacer(Modifier.height(10.dp))
                    Text(if (expanded) "−  Less" else "+  Expand", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
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
    Row(
        Modifier.fillMaxWidth().clickable { onReading(reading) }.alpha(if (reading.done) .56f else 1f).padding(vertical = 12.dp, horizontal = 3.dp),
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
                    Text("Done", Modifier.background(DoneSoft, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 3.dp), color = Green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Text(reading.progress.label, color = Muted, fontSize = 10.sp)
            }
        }
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(if (reading.done) Green else Color.White)
                .border(1.5.dp, if (reading.done) Green else Muted.copy(alpha = .55f), CircleShape)
                .clickable { onToggleDone(reading.vid) },
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
                Button(onClick = { onToggleDone(reading.vid) }, modifier = Modifier.fillMaxWidth().padding(top = 9.dp), colors = ButtonDefaults.buttonColors(containerColor = if (reading.done) Soft else Teal)) {
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
