package org.isdm.companion.platform

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.isdm.companion.CompanionApplication
import org.isdm.companion.engine.Command
import org.isdm.companion.engine.CommandResult
import org.isdm.companion.engine.LMS_ZONE
import org.isdm.companion.engine.MonitoringMode
import org.isdm.companion.ui.MainActivity
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

class MonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    private val app
        get() = application as CompanionApplication

    private val engine
        get() = app.engine

    override fun onCreate() {
        super.onCreate()
        AndroidNotifier.createChannels(this)
        app.diagnostics.log("monitor_service_created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app.autoAttendanceStore.setEnabled(false)
            app.autoAttendanceScheduler.cancel()
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
        app.diagnostics.log("monitor_service_started", mapOf("source" to if (autoArm) "alarm" else "user"))
        if (autoArm) {
            val stopAt = intent.getLongExtra(EXTRA_STOP_AT, 0L)
            val sessionIds = intent.getStringArrayListExtra(EXTRA_SESSION_IDS).orEmpty().toSet()
            scope.launch { armFromAlarm(stopAt, sessionIds) }
        } else {
            startMonitorLoop()
        }
        return START_NOT_STICKY
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
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun armFromAlarm(stopAtMillis: Long, sessionIds: Set<String>) {
        val credentials = app.credentialStore.load()
        if (credentials == null) {
            app.diagnostics.log("auto_attendance_skipped", mapOf("reason" to "credentials_missing"))
            stopMonitoring()
            return
        }
        if (!engine.state.value.credentialsConfigured) {
            engine.dispatch(Command.ConfigureCredentials(credentials.email, credentials.password))
        }
        if (engine.dispatch(Command.RefreshToday) is CommandResult.Rejected) {
            app.diagnostics.log("auto_attendance_skipped", mapOf("reason" to "refresh_rejected"))
            stopMonitoring()
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
            stopMonitoring()
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
                stopMonitoring()
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
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
        monitorJob = scope.launch {
            while (isActive) {
                engine.dispatch(Command.MonitorTick)
                if (!engine.state.value.monitor.active) {
                    stopMonitoring()
                    break
                }
                delay(POLL_MS)
            }
        }
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
