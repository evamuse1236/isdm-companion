package org.isdm.companion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.isdm.companion.engine.CompanionState
import org.isdm.companion.engine.EngineError
import org.isdm.companion.engine.MonitoringMode
import org.isdm.companion.engine.Session
import org.isdm.companion.engine.SessionState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val viewModel: CompanionViewModel by viewModels()
    private var pendingMonitoringMode = MonitoringMode.NOTIFY_ONLY

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startMonitoring(pendingMonitoringMode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = companionColors()) {
                CompanionScreen(
                    viewModel = viewModel,
                    onStartMonitoring = ::requestMonitoring,
                )
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val nid = intent?.getStringExtra(EXTRA_MARK_NID) ?: return
        intent.removeExtra(EXTRA_MARK_NID)
        viewModel.handleMarkIntent(nid)
    }

    private fun requestMonitoring(mode: MonitoringMode) {
        pendingMonitoringMode = mode
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startMonitoring(mode)
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_MARK_NID = "mark_nid"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionScreen(
    viewModel: CompanionViewModel,
    onStartMonitoring: (MonitoringMode) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val initializing by viewModel.initializing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ISDM Companion", fontWeight = FontWeight.SemiBold)
                        Text(
                            state.identity?.displayName ?: "Your class day, in your pocket",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (state.credentialsConfigured) {
                        IconButton(onClick = viewModel::refresh, enabled = !state.sync.inProgress) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            initializing -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.identity == null -> LoginContent(
                state = state,
                onSignIn = viewModel::signIn,
                modifier = Modifier.padding(padding),
            )

            else -> TodayContent(
                state = state,
                onMark = viewModel::mark,
                onStartMonitoring = onStartMonitoring,
                onStopMonitoring = viewModel::stopMonitoring,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun LoginContent(
    state: CompanionState,
    onSignIn: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Sign in to the LMS", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your login is encrypted on this phone and sent only to lms.isdm.org.in.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("LMS email") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("LMS password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(errorText(it), color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onSignIn(email, password) },
            enabled = !state.sync.inProgress,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.sync.inProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Sign in")
            }
        }
    }
}

@Composable
private fun TodayContent(
    state: CompanionState,
    onMark: (String) -> Unit,
    onStartMonitoring: (MonitoringMode) -> Unit,
    onStopMonitoring: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MonitoringCard(state, onStartMonitoring, onStopMonitoring)
        }
        state.error?.let { error ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        errorText(error),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        if (state.sync.inProgress && state.sessions.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (state.sessions.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp)) {
                        Text("No classes today", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Pull out the laptop only if you miss it.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(state.sessions, key = { it.nid ?: it.eventNid ?: "${it.name}-${it.start}" }) { session ->
                SessionCard(session, onMark)
            }
        }
    }
}

@Composable
private fun MonitoringCard(
    state: CompanionState,
    onStart: (MonitoringMode) -> Unit,
    onStop: () -> Unit,
) {
    val active = state.monitor.active
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                if (active && state.monitor.mode == MonitoringMode.AUTO_MARK) "Auto-mark armed today"
                else if (active) "Monitoring today"
                else "Class-day monitoring is off",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (active && state.monitor.mode == MonitoringMode.AUTO_MARK) {
                    "Auto-mark · checks every 30 seconds · stops at 6:00 PM"
                } else if (active) "Notify-only · checks every 30 seconds · stops at 6:00 PM"
                else "Start it when you arrive. Tomorrow always requires a fresh start.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (active) {
                OutlinedButton(onClick = onStop) { Text("Stop monitoring") }
            } else {
                Button(onClick = { onStart(MonitoringMode.NOTIFY_ONLY) }) {
                    Text("Start today’s monitoring")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onStart(MonitoringMode.AUTO_MARK) }) {
                    Text("Arm auto-mark for today")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Only arm auto-mark when you are physically attending today’s classes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SessionCard(session: Session, onMark: (String) -> Unit) {
    val accent = when (session.state) {
        SessionState.OPEN -> Color(0xFFB42318)
        SessionState.MARKED -> Color(0xFF067647)
        SessionState.UPCOMING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    session.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    session.state.label,
                    color = accent,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${TIME_FORMAT.format(session.start.atZone(IST))} – ${TIME_FORMAT.format(session.end.atZone(IST))}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val place = listOfNotNull(session.room, session.floorLabel).joinToString(" · ")
            if (place.isNotBlank()) Text(place, color = MaterialTheme.colorScheme.onSurfaceVariant)
            session.trainer?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (session.state == SessionState.OPEN && session.nid != null) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { onMark(session.nid) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB42318)),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Mark present") }
            }
        }
    }
}

private val SessionState.label: String
    get() = when (this) {
        SessionState.MARKED -> "PRESENT"
        SessionState.OPEN -> "OPEN NOW"
        SessionState.UPCOMING -> "UPCOMING"
        SessionState.MISSED -> "MISSED"
        SessionState.NO_ATTENDANCE -> "NO MARK"
        SessionState.DONE -> "DONE"
    }

private fun errorText(error: EngineError): String = when (error) {
    EngineError.CredentialsMissing -> "Enter your LMS login to continue."
    is EngineError.InvalidCommand -> error.message
    is EngineError.AuthenticationFailed -> error.message
    is EngineError.NetworkFailure -> "The LMS could not be reached. Check your connection and retry."
    is EngineError.LmsFailure -> error.message
    is EngineError.MarkWindowClosed -> "The LMS marking window is no longer open."
    is EngineError.MarkRejected -> error.message
    EngineError.SystemLimitReached -> "Android’s monitoring time limit was reached. Open the app to restart."
}

@Composable
private fun companionColors() = androidx.compose.material3.lightColorScheme(
    primary = Color(0xFF115E59),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    background = Color(0xFFF8F6F0),
    surface = Color(0xFFFFFBF5),
    surfaceVariant = Color(0xFFEDE9E0),
    error = Color(0xFFB42318),
)

private val IST = ZoneId.of("Asia/Kolkata")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")
