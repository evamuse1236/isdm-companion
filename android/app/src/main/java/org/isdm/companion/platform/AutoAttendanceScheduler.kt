package org.isdm.companion.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import org.isdm.companion.CompanionApplication
import org.isdm.companion.engine.CompanionSession
import org.isdm.companion.engine.LMS_ZONE
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

class AutoAttendanceStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun scheduledIds(): Set<Int> = preferences.getStringSet(KEY_ALARM_IDS, emptySet())
        .orEmpty()
        .mapNotNull(String::toIntOrNull)
        .toSet()

    fun setScheduledIds(ids: Set<Int>) {
        preferences.edit().putStringSet(KEY_ALARM_IDS, ids.map(Int::toString).toSet()).apply()
    }

    private companion object {
        const val FILE_NAME = "auto_attendance"
        const val KEY_ENABLED = "enabled"
        const val KEY_ALARM_IDS = "scheduled_alarm_ids"
    }
}

data class AutoAttendanceWindow(
    val requestId: Int,
    val sessionId: String,
    val alarmAt: Instant,
    val stopAt: Instant,
)

sealed interface AutoAttendanceScheduleResult {
    data object Disabled : AutoAttendanceScheduleResult
    data object LocationPermissionRequired : AutoAttendanceScheduleResult
    data object ExactAlarmPermissionRequired : AutoAttendanceScheduleResult
    data class Scheduled(val windows: List<AutoAttendanceWindow>) : AutoAttendanceScheduleResult
    data class Failed(val error: RuntimeException) : AutoAttendanceScheduleResult
}

class AutoAttendanceScheduler(
    context: Context,
    private val store: AutoAttendanceStore,
) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun schedule(sessions: List<CompanionSession>, now: Instant): AutoAttendanceScheduleResult {
        cancel()
        if (!store.isEnabled()) return AutoAttendanceScheduleResult.Disabled
        if (!hasAttendanceLocationAccess()) return AutoAttendanceScheduleResult.LocationPermissionRequired
        if (!canScheduleExactAlarms()) return AutoAttendanceScheduleResult.ExactAlarmPermissionRequired

        val windows = autoAttendanceWindows(sessions, now)
        return try {
            windows.forEach { window ->
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    window.alarmAt.toEpochMilli(),
                    pendingIntent(window.requestId, window.sessionId, window.stopAt),
                )
            }
            store.setScheduledIds(windows.mapTo(mutableSetOf()) { it.requestId })
            AutoAttendanceScheduleResult.Scheduled(windows)
        } catch (error: RuntimeException) {
            AutoAttendanceScheduleResult.Failed(error)
        }
    }

    fun cancel() {
        store.scheduledIds().forEach { requestId ->
            val intent = alarmIntent(requestId, sessionId = "", stopAt = Instant.EPOCH)
            PendingIntent.getBroadcast(
                appContext,
                requestId,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let(alarmManager::cancel)
        }
        store.setScheduledIds(emptySet())
    }

    private fun hasAttendanceLocationAccess(): Boolean {
        val precise = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return precise && background
    }

    private fun pendingIntent(requestId: Int, sessionId: String, stopAt: Instant): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            requestId,
            alarmIntent(requestId, sessionId, stopAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun alarmIntent(requestId: Int, sessionId: String, stopAt: Instant): Intent =
        Intent(appContext, AutoAttendanceAlarmReceiver::class.java)
            .setAction(ACTION_AUTO_ATTENDANCE)
            .setData(Uri.parse("isdm-companion://auto-attendance/$requestId"))
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .putExtra(EXTRA_STOP_AT, stopAt.toEpochMilli())

    private companion object {
        const val ACTION_AUTO_ATTENDANCE = "org.isdm.companion.AUTO_ATTENDANCE_ALARM"
    }
}

class AutoAttendanceAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CompanionApplication
        if (!app.autoAttendanceStore.isEnabled()) return

        val stopAt = intent.getLongExtra(EXTRA_STOP_AT, 0L)
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        app.diagnostics.log("auto_attendance_alarm", mapOf("session_id" to sessionId))
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitoringService::class.java)
                    .setAction(MonitoringService.ACTION_AUTO_ARM)
                    .putExtra(EXTRA_STOP_AT, stopAt),
            )
        }.onFailure { app.diagnostics.log("auto_attendance_service_start_failed", error = it) }
    }
}

internal fun autoAttendanceWindows(
    sessions: List<CompanionSession>,
    now: Instant,
): List<AutoAttendanceWindow> = sessions.mapNotNull { session ->
    if (session.marked) return@mapNotNull null
    val sessionId = session.nid ?: return@mapNotNull null
    val localStart = session.start.atZone(LMS_ZONE)
    val cutoff = localStart.toLocalDate().atTime(LocalTime.of(18, 0)).atZone(LMS_ZONE).toInstant()
    val stopAt = minOf(session.end.plus(POST_SESSION_GRACE), cutoff)
    if (!stopAt.isAfter(now)) return@mapNotNull null

    val requestedAlarm = session.start.minus(PRE_SESSION_LEAD)
    val alarmAt = maxOf(requestedAlarm, now.plusSeconds(MINIMUM_ALARM_DELAY_SECONDS))
    if (!stopAt.isAfter(alarmAt)) return@mapNotNull null

    AutoAttendanceWindow(
        requestId = "$sessionId:${localStart.toLocalDate()}".hashCode() and Int.MAX_VALUE,
        sessionId = sessionId,
        alarmAt = alarmAt,
        stopAt = stopAt,
    )
}.distinctBy(AutoAttendanceWindow::requestId).sortedBy(AutoAttendanceWindow::alarmAt)

internal const val EXTRA_STOP_AT = "monitor_stop_at"
private const val EXTRA_SESSION_ID = "monitor_session_id"
private const val MINIMUM_ALARM_DELAY_SECONDS = 3L
private val PRE_SESSION_LEAD: Duration = Duration.ofMinutes(10)
private val POST_SESSION_GRACE: Duration = Duration.ofMinutes(15)
