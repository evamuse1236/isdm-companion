package org.isdm.companion.platform

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import org.isdm.companion.engine.DiagnosticsLogger

/** Reports Android's explanation for previous process exits without collecting stack traces. */
class AndroidProcessExitReporter(
    context: Context,
    private val diagnostics: DiagnosticsLogger,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun reportPreviousExits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val activityManager = appContext.getSystemService(ActivityManager::class.java) ?: return
        val lastReportedAt = preferences.getLong(LAST_REPORTED_AT, 0L)
        val exits = runCatching {
            activityManager.getHistoricalProcessExitReasons(appContext.packageName, 0, 10)
        }.getOrElse { return }
            .filter { it.timestamp > lastReportedAt }

        exits.forEach { exit ->
            diagnostics.log(
                "previous_process_exit",
                mapOf(
                    "importance" to exit.importance.toString(),
                    "reason" to processExitReasonLabel(exit.reason),
                    "timestamp" to exit.timestamp.toString(),
                ),
            )
        }
        exits.maxOfOrNull { it.timestamp }?.let { newest ->
            preferences.edit().putLong(LAST_REPORTED_AT, newest).apply()
        }
    }

    private companion object {
        const val PREFERENCES = "process_exit_reporting"
        const val LAST_REPORTED_AT = "last_reported_at"
    }
}

internal fun processExitReasonLabel(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_ANR -> "anr"
    ApplicationExitInfo.REASON_CRASH -> "crash"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
    ApplicationExitInfo.REASON_OTHER -> "other"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
    ApplicationExitInfo.REASON_SIGNALED -> "signaled"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
    else -> "unknown"
}
