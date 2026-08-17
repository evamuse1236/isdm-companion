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
import org.isdm.companion.engine.AUTO_ATTENDANCE_GRACE
import org.isdm.companion.engine.AUTO_ATTENDANCE_LEAD
import org.isdm.companion.engine.CompanionSession
import org.isdm.companion.engine.DiagnosticsLogger
import org.isdm.companion.engine.LMS_ZONE
import java.time.Instant
import java.time.LocalTime

class AutoAttendanceStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean): Boolean =
        preferences.edit().putBoolean(KEY_ENABLED, enabled).commit()

    fun scheduledIds(): Set<Int> = preferences.getStringSet(KEY_ALARM_IDS, emptySet())
        .orEmpty()
        .mapNotNull(String::toIntOrNull)
        .toSet()

    fun setScheduledIds(ids: Set<Int>): Boolean =
        preferences.edit().putStringSet(KEY_ALARM_IDS, ids.map(Int::toString).toSet()).commit()

    private companion object {
        const val FILE_NAME = "auto_attendance"
        const val KEY_ENABLED = "enabled"
        const val KEY_ALARM_IDS = "scheduled_alarm_ids"
    }
}

data class AutoAttendanceWindow(
    val requestId: Int,
    val sessionId: String,
    val targetSessionIds: Set<String>,
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
    private val diagnostics: DiagnosticsLogger,
) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun hasRequiredSystemAccess(): Boolean = hasAttendanceLocationAccess() && canScheduleExactAlarms()

    fun schedule(sessions: List<CompanionSession>, now: Instant): AutoAttendanceScheduleResult {
        val existingAlarmsCancelled = cancel()
        val result = when {
            !existingAlarmsCancelled -> AutoAttendanceScheduleResult.Failed(
                IllegalStateException("Could not cancel previously scheduled alarms"),
            )
            !store.isEnabled() -> AutoAttendanceScheduleResult.Disabled
            !hasAttendanceLocationAccess() -> AutoAttendanceScheduleResult.LocationPermissionRequired
            !canScheduleExactAlarms() -> AutoAttendanceScheduleResult.ExactAlarmPermissionRequired
            else -> {
                val windows = autoAttendanceWindows(sessions, now)
                val previouslyUncancelledIds = store.scheduledIds()
                val intendedIds = windows.mapTo(mutableSetOf()) { it.requestId }
                if (!store.setScheduledIds(previouslyUncancelledIds + intendedIds)) {
                    AutoAttendanceScheduleResult.Failed(
                        IllegalStateException("Could not persist alarm IDs before scheduling"),
                    )
                } else {
                    val outcome = installAutoAttendanceWindowsAtomically(
                        windows = windows,
                        install = { window ->
                            alarmManager.setExactAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP,
                                window.alarmAt.toEpochMilli(),
                                pendingIntent(window.requestId, window.targetSessionIds, window.stopAt),
                            )
                        },
                        cancel = ::cancelAlarm,
                    )
                    if (outcome.error == null) {
                        AutoAttendanceScheduleResult.Scheduled(windows)
                    } else {
                        val tracked = store.setScheduledIds(previouslyUncancelledIds + outcome.rollbackFailedIds)
                        val error = if (tracked) {
                            outcome.error
                        } else {
                            IllegalStateException("Could not persist alarm IDs after rollback").apply {
                                addSuppressed(outcome.error)
                            }
                        }
                        AutoAttendanceScheduleResult.Failed(error)
                    }
                }
            }
        }
        diagnostics.log(
            "auto_attendance_schedule_evaluated",
            mapOf(
                "attendance_sessions" to sessions.count { it.nid != null }.toString(),
                "outcome" to when (result) {
                    AutoAttendanceScheduleResult.Disabled -> "disabled"
                    AutoAttendanceScheduleResult.LocationPermissionRequired -> "location_permission_required"
                    AutoAttendanceScheduleResult.ExactAlarmPermissionRequired -> "exact_alarm_permission_required"
                    is AutoAttendanceScheduleResult.Scheduled -> "scheduled"
                    is AutoAttendanceScheduleResult.Failed -> "failed"
                },
                "windows" to ((result as? AutoAttendanceScheduleResult.Scheduled)?.windows?.size ?: 0).toString(),
            ),
            (result as? AutoAttendanceScheduleResult.Failed)?.error,
        )
        return result
    }

    fun cancel(): Boolean {
        val scheduledIds = store.scheduledIds()
        val remainingIds = scheduledIds.filterNotTo(mutableSetOf(), ::cancelAlarm)
        val tracked = store.setScheduledIds(remainingIds)
        if (scheduledIds.isNotEmpty()) {
            diagnostics.log(
                "auto_attendance_alarms_cancelled",
                mapOf(
                    "alarms" to (scheduledIds.size - remainingIds.size).toString(),
                    "remaining" to remainingIds.size.toString(),
                ),
            )
        }
        return remainingIds.isEmpty() && tracked
    }

    private fun cancelAlarm(requestId: Int): Boolean = runCatching {
        val intent = alarmIntent(requestId, targetSessionIds = emptySet(), stopAt = Instant.EPOCH)
        PendingIntent.getBroadcast(
            appContext,
            requestId,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let(alarmManager::cancel)
    }.onFailure { error ->
        diagnostics.log("auto_attendance_alarm_cancel_failed", error = error)
    }.isSuccess

    private fun hasAttendanceLocationAccess(): Boolean {
        val precise = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return precise && background
    }

    private fun pendingIntent(requestId: Int, targetSessionIds: Set<String>, stopAt: Instant): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            requestId,
            alarmIntent(requestId, targetSessionIds, stopAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun alarmIntent(requestId: Int, targetSessionIds: Set<String>, stopAt: Instant): Intent =
        Intent(appContext, AutoAttendanceAlarmReceiver::class.java)
            .setAction(ACTION_AUTO_ATTENDANCE)
            .setData(Uri.parse("isdm-companion://auto-attendance/$requestId"))
            .putStringArrayListExtra(EXTRA_SESSION_IDS, ArrayList(targetSessionIds))
            .putExtra(EXTRA_STOP_AT, stopAt.toEpochMilli())

    private companion object {
        const val ACTION_AUTO_ATTENDANCE = "org.isdm.companion.AUTO_ATTENDANCE_ALARM"
    }
}

internal data class AlarmInstallationOutcome(
    val error: RuntimeException? = null,
    val rollbackFailedIds: Set<Int> = emptySet(),
)

internal fun installAutoAttendanceWindowsAtomically(
    windows: List<AutoAttendanceWindow>,
    install: (AutoAttendanceWindow) -> Unit,
    cancel: (Int) -> Boolean,
): AlarmInstallationOutcome {
    val installed = mutableListOf<Int>()
    return try {
        windows.forEach { window ->
            install(window)
            installed += window.requestId
        }
        AlarmInstallationOutcome()
    } catch (error: RuntimeException) {
        val rollbackFailedIds = installed.filterNotTo(mutableSetOf(), cancel)
        AlarmInstallationOutcome(error, rollbackFailedIds)
    }
}

class AutoAttendanceAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CompanionApplication
        if (!app.autoAttendanceStore.isEnabled()) return

        val stopAt = intent.getLongExtra(EXTRA_STOP_AT, 0L)
        val sessionIds = intent.getStringArrayListExtra(EXTRA_SESSION_IDS).orEmpty()
        app.diagnostics.log("auto_attendance_alarm", mapOf("session_ids" to sessionIds.joinToString(",")))
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitoringService::class.java)
                    .setAction(MonitoringService.ACTION_AUTO_ARM)
                    .putStringArrayListExtra(EXTRA_SESSION_IDS, ArrayList(sessionIds))
                    .putExtra(EXTRA_STOP_AT, stopAt),
            )
        }.onFailure { app.diagnostics.log("auto_attendance_service_start_failed", error = it) }
    }
}

class AutoAttendanceRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CompanionApplication
        if (!app.autoAttendanceStore.isEnabled()) return
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        app.diagnostics.log("auto_attendance_restore_requested", mapOf("action" to intent.action.orEmpty()))
        app.credentialStore.load()?.let { credentials ->
            app.localStore.selectAccount(credentials.email)
            app.localStore.loadSchedule()?.let { cached ->
                app.autoAttendanceScheduler.schedule(cached.sessions, Instant.now())
            }
        }
        CompanionSyncWorker.enqueueImmediate(context)
    }
}

internal fun autoAttendanceWindows(
    sessions: List<CompanionSession>,
    now: Instant,
): List<AutoAttendanceWindow> {
    val windows = sessions.mapNotNull { session ->
        if (session.marked) return@mapNotNull null
        val sessionId = session.nid ?: return@mapNotNull null
        val localStart = session.start.atZone(LMS_ZONE)
        val cutoff = localStart.toLocalDate().atTime(LocalTime.of(18, 0)).atZone(LMS_ZONE).toInstant()
        val stopAt = minOf(session.end.plus(AUTO_ATTENDANCE_GRACE), cutoff)
        if (!stopAt.isAfter(now)) return@mapNotNull null

        val requestedAlarm = session.start.minus(AUTO_ATTENDANCE_LEAD)
        val alarmAt = maxOf(requestedAlarm, now.plusSeconds(MINIMUM_ALARM_DELAY_SECONDS))
        if (!stopAt.isAfter(alarmAt)) return@mapNotNull null

        AutoAttendanceWindow(
            requestId = "$sessionId:${localStart.toLocalDate()}".hashCode() and Int.MAX_VALUE,
            sessionId = sessionId,
            targetSessionIds = setOf(sessionId),
            alarmAt = alarmAt,
            stopAt = stopAt,
        )
    }.distinctBy(AutoAttendanceWindow::requestId)

    return windows.map { window ->
        val overlapping = windows.filter { other ->
            other.alarmAt < window.stopAt && window.alarmAt < other.stopAt
        }
        window.copy(
            targetSessionIds = overlapping.mapTo(mutableSetOf(), AutoAttendanceWindow::sessionId),
            stopAt = overlapping.maxOf(AutoAttendanceWindow::stopAt),
        )
    }.sortedBy(AutoAttendanceWindow::alarmAt)
}

internal const val EXTRA_STOP_AT = "monitor_stop_at"
internal const val EXTRA_SESSION_IDS = "monitor_session_ids"
private const val MINIMUM_ALARM_DELAY_SECONDS = 3L
