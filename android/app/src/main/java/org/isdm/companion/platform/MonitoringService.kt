package org.isdm.companion.platform

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import org.isdm.companion.CompanionApplication
import org.isdm.companion.engine.Command
import org.isdm.companion.engine.CommandResult
import org.isdm.companion.engine.EngineError
import org.isdm.companion.engine.LMS_ZONE
import org.isdm.companion.engine.MonitoringMode
import org.isdm.companion.ui.MainActivity
import java.time.Instant
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale

class MonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private val wakeLockGuard = Any()
    private var monitorWakeLock: PowerManager.WakeLock? = null
    private var monitorWakeLockRenewAtElapsedMillis = 0L
    private lateinit var autoArmRequests: AutoArmRequestProcessor

    private val app
        get() = application as CompanionApplication

    private val engine
        get() = app.engine

    override fun onCreate() {
        super.onCreate()
        AndroidNotifier.createChannels(this)
        autoArmRequests = AutoArmRequestProcessor(
            scope = scope,
            handle = { request -> armFromAlarm(request.stopAtMillis, request.sessionIds) },
            onIdle = { startId ->
                if (!engine.state.value.monitor.active && stopSelfResult(startId)) {
                    releaseMonitorWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            },
        )
        app.diagnostics.log("monitor_service_created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            autoArmRequests.cancel()
            scope.launch {
                engine.dispatch(Command.DisarmMonitoring)
                stopMonitoring()
            }
            return START_NOT_STICKY
        }

        val autoArm = intent?.action == ACTION_AUTO_ARM
        if (!startForegroundSafely(monitorNotification(starting = autoArm))) {
            stopSelf()
            return START_NOT_STICKY
        }
        app.diagnostics.log(
            "monitor_service_started",
            mapOf(
                "source" to if (autoArm) "alarm" else "user",
                "fgs_type" to "location",
            ),
        )
        if (autoArm) {
            val stopAt = intent.getLongExtra(EXTRA_STOP_AT, 0L)
            val sessionIds = intent.getStringArrayListExtra(EXTRA_SESSION_IDS).orEmpty().toSet()
            acquireMonitorWakeLock(stopAt.takeIf { it > 0L }?.let(Instant::ofEpochMilli))
            autoArmRequests.submit(AutoArmRequest(startId, stopAt, sessionIds))
        } else {
            startMonitorLoop()
        }
        return START_REDELIVER_INTENT
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        app.diagnostics.log("monitor_service_timeout", mapOf("fgs_type" to fgsType.toString()))
        scope.launch {
            engine.dispatch(Command.SystemLimitReached)
            stopMonitoring()
        }
    }

    override fun onDestroy() {
        app.diagnostics.log("monitor_service_destroyed")
        monitorJob?.cancel()
        releaseMonitorWakeLock()
        autoArmRequests.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun armFromAlarm(stopAtMillis: Long, sessionIds: Set<String>) {
        val credentials = app.credentialStore.load()
        if (credentials == null) {
            app.diagnostics.log("auto_attendance_skipped", mapOf("reason" to "credentials_missing"))
            return
        }
        if (!engine.state.value.credentialsConfigured) {
            engine.dispatch(Command.ConfigureCredentials(credentials.email, credentials.password))
        }
        var refreshAttempt = 0
        val nowMillis = System.currentTimeMillis()
        val refreshDeadline = autoArmRefreshDeadlineMillis(nowMillis, stopAtMillis)
        if (refreshDeadline == null) {
            app.diagnostics.log("auto_attendance_skipped", mapOf("reason" to "window_expired"))
            return
        }
        val refreshed = runRetryableUntil(
            deadlineMillis = refreshDeadline,
            nowMillis = System::currentTimeMillis,
            pause = { delay(it) },
        ) {
            refreshAttempt += 1
            when (val result = engine.dispatch(Command.RefreshToday)) {
                is CommandResult.Completed -> RetryableOperationResult.SUCCESS
                is CommandResult.Rejected -> {
                    val terminal = result.error is EngineError.AuthenticationFailed
                    app.diagnostics.log(
                        "auto_attendance_refresh_failed",
                        mapOf(
                            "attempt" to refreshAttempt.toString(),
                            "error" to result.error.javaClass.simpleName,
                            "retry" to (!terminal).toString(),
                        ),
                    )
                    if (terminal) RetryableOperationResult.STOP else RetryableOperationResult.RETRY
                }
                else -> RetryableOperationResult.SUCCESS
            }
        }
        if (!refreshed) {
            app.diagnostics.log("auto_attendance_skipped", mapOf("reason" to "refresh_exhausted"))
            return
        }

        val stopTime = stopAtMillis.takeIf { it > 0 }
            ?.let(Instant::ofEpochMilli)
            ?.atZone(LMS_ZONE)
            ?.toLocalTime()
        val liveSessionIds = engine.state.value.sessions
            .filterNot { it.marked }
            .mapNotNullTo(mutableSetOf()) { it.nid }
        val targetSessionIds = sessionIds.intersect(liveSessionIds)
        if (targetSessionIds.isEmpty()) {
            app.diagnostics.log("auto_attendance_skipped", mapOf("reason" to "targets_missing"))
            return
        }
        for (sessionId in targetSessionIds) {
            val result = engine.dispatch(
                Command.ArmMonitoring(
                    mode = MonitoringMode.AUTO_MARK,
                    stopAt = stopTime,
                    targetSessionId = sessionId,
                ),
            )
            if (result !is CommandResult.Completed) {
                app.diagnostics.log("auto_attendance_skipped", mapOf("reason" to "arm_rejected"))
                return
            }
        }
        if (!startForegroundSafely(monitorNotification())) {
            stopMonitoring()
            return
        }
        startMonitorLoop()
    }

    private fun startForegroundSafely(notification: Notification): Boolean = runCatching {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )
    }.onFailure { error ->
        app.diagnostics.log("monitor_foreground_start_failed", error = error)
    }.isSuccess

    private fun startMonitorLoop() {
        if (monitorJob?.isActive == true) return
        ensureMonitorWakeLock()
        monitorJob = scope.launch {
            while (isActive) {
                ensureMonitorWakeLock()
                val monitor = engine.state.value.monitor
                if (monitor.stopAt?.let { !Instant.now().isBefore(it) } == true) {
                    engine.dispatch(Command.MonitorTick)
                } else if (monitor.mode == MonitoringMode.AUTO_MARK) {
                    runCatching { app.betaManager.autoPreflight() }
                        .onSuccess { decision ->
                            if (decision.allowed) {
                                engine.dispatch(Command.MonitorTick)
                            } else {
                                app.diagnostics.log(
                                    "auto_attendance_remote_blocked",
                                    mapOf("reason" to decision.reason),
                                )
                                engine.dispatch(Command.DisarmMonitoring)
                            }
                        }
                        .onFailure { error ->
                            app.diagnostics.log("auto_attendance_preflight_failed", error = error)
                        }
                } else {
                    engine.dispatch(Command.MonitorTick)
                }
                if (!engine.state.value.monitor.active) {
                    stopMonitoring()
                    break
                }
                delay(POLL_MS)
            }
        }
    }

    private fun ensureMonitorWakeLock() {
        synchronized(wakeLockGuard) {
            if (shouldRenewMonitorWakeLock(
                    isHeld = monitorWakeLock?.isHeld == true,
                    elapsedMillis = SystemClock.elapsedRealtime(),
                    renewAtMillis = monitorWakeLockRenewAtElapsedMillis,
                )
            ) {
                acquireMonitorWakeLockLocked(engine.state.value.monitor.stopAt)
            }
        }
    }

    private fun acquireMonitorWakeLock(stopAt: Instant?) {
        synchronized(wakeLockGuard) { acquireMonitorWakeLockLocked(stopAt) }
    }

    private fun acquireMonitorWakeLockLocked(stopAt: Instant?) {
        releaseMonitorWakeLockLocked()
        val timeout = monitorWakeLockTimeoutMillis(Instant.now(), stopAt)
        monitorWakeLockRenewAtElapsedMillis = SystemClock.elapsedRealtime() +
            (timeout - WAKE_LOCK_RENEW_MARGIN_MS).coerceAtLeast(POLL_MS)
        val powerManager = getSystemService(PowerManager::class.java)
        monitorWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:auto-attendance-monitor",
        ).apply { acquire(timeout) }
        app.diagnostics.log("monitor_wake_lock_acquired", mapOf("timeout_ms" to timeout.toString()))
    }

    private fun releaseMonitorWakeLock() {
        synchronized(wakeLockGuard) { releaseMonitorWakeLockLocked() }
    }

    private fun releaseMonitorWakeLockLocked() {
        monitorWakeLock?.takeIf { it.isHeld }?.release()
        monitorWakeLock = null
        monitorWakeLockRenewAtElapsedMillis = 0L
    }

    private fun monitorNotification(starting: Boolean = false): Notification {
        val mode = engine.state.value.monitor.mode
        val stopLabel = engine.state.value.monitor.stopAt
            ?.atZone(LMS_ZONE)
            ?.let(STOP_TIME_FORMAT::format)
            ?: "6:00 PM"
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, MonitoringService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, AndroidNotifier.MONITOR_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(
                if (starting) "ISDM Companion is starting auto attendance"
                else if (mode == MonitoringMode.AUTO_MARK) "ISDM Companion auto-mark is armed"
                else "ISDM Companion is monitoring today",
            )
            .setContentText(
                if (starting) "Preparing this class window"
                else if (mode == MonitoringMode.AUTO_MARK) "Auto-mark · checks every 30 seconds · stops at $stopLabel"
                else "Notify-only · checks every 30 seconds · stops at $stopLabel",
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun stopMonitoring() {
        app.diagnostics.log("monitor_service_stopping")
        monitorJob?.cancel()
        monitorJob = null
        releaseMonitorWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val NOTIFICATION_ID = 10_001
        const val ACTION_STOP = "org.isdm.companion.STOP_MONITORING"
        const val ACTION_AUTO_ARM = "org.isdm.companion.AUTO_ARM_MONITORING"
        private const val POLL_MS = 30_000L
        private val STOP_TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    }
}

internal fun monitorWakeLockTimeoutMillis(now: Instant, stopAt: Instant?): Long {
    val requested = stopAt
        ?.let { Duration.between(now, it).toMillis() + WAKE_LOCK_RELEASE_GRACE_MS }
        ?: MAX_WAKE_LOCK_MS
    return requested.coerceIn(MIN_WAKE_LOCK_MS, MAX_WAKE_LOCK_MS)
}

internal fun shouldRenewMonitorWakeLock(
    isHeld: Boolean,
    elapsedMillis: Long,
    renewAtMillis: Long,
): Boolean = !isHeld || elapsedMillis >= renewAtMillis

internal fun autoArmRefreshDeadlineMillis(nowMillis: Long, stopAtMillis: Long): Long? = when {
    stopAtMillis <= 0L -> nowMillis + INITIAL_REFRESH_RETRY_WINDOW_MS
    stopAtMillis <= nowMillis -> null
    else -> stopAtMillis
}

private const val MIN_WAKE_LOCK_MS = 60_000L
private const val WAKE_LOCK_RELEASE_GRACE_MS = 60_000L
private const val WAKE_LOCK_RENEW_MARGIN_MS = 60_000L
private const val MAX_WAKE_LOCK_MS = 2 * 60 * 60_000L
private const val INITIAL_REFRESH_RETRY_WINDOW_MS = 10 * 60_000L

internal enum class RetryableOperationResult { SUCCESS, RETRY, STOP }

internal suspend fun runAutoAttendanceTick(
    preflight: suspend () -> AutoPreflight,
    tick: suspend () -> Unit,
): Boolean = try {
    val result = preflight()
    if (!result.allowed) false else {
        tick()
        true
    }
} catch (error: CancellationException) {
    throw error
} catch (_: Throwable) {
    false
}

internal suspend fun runRetryableUntil(
    deadlineMillis: Long,
    nowMillis: () -> Long,
    pause: suspend (Long) -> Unit,
    operation: suspend () -> RetryableOperationResult,
): Boolean {
    while (nowMillis() < deadlineMillis) {
        when (operation()) {
            RetryableOperationResult.SUCCESS -> return true
            RetryableOperationResult.STOP -> return false
            RetryableOperationResult.RETRY -> {
                val remaining = deadlineMillis - nowMillis()
                if (remaining <= 0L) return false
                pause(minOf(RETRY_DELAY_MS, remaining))
            }
        }
    }
    return false
}

private const val RETRY_DELAY_MS = 30_000L

internal data class AutoArmRequest(
    val startId: Int,
    val stopAtMillis: Long,
    val sessionIds: Set<String>,
)

internal class AutoArmRequestProcessor(
    scope: CoroutineScope,
    private val handle: suspend (AutoArmRequest) -> Unit,
    private val onIdle: (Int) -> Unit = {},
) {
    private val requests = Channel<AutoArmRequest>(Channel.UNLIMITED)
    private val lock = Any()
    private var pending = 0

    private val worker = scope.launch {
        for (request in requests) {
            try {
                handle(request)
            } finally {
                val idle = synchronized(lock) {
                    pending -= 1
                    pending == 0
                }
                if (idle) onIdle(request.startId)
            }
        }
    }

    fun submit(request: AutoArmRequest) {
        synchronized(lock) { pending += 1 }
        if (requests.trySend(request).isFailure) {
            synchronized(lock) { pending -= 1 }
        }
    }

    fun cancel() {
        requests.close()
        worker.cancel()
    }
}
