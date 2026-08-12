package org.isdm.companion.platform

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.isdm.companion.CompanionApplication
import org.isdm.companion.engine.Command
import org.isdm.companion.engine.CommandResult
import org.isdm.companion.engine.EngineError
import java.time.Duration
import java.util.concurrent.TimeUnit

class CompanionSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CompanionApplication
        app.diagnostics.log("background_sync_started", mapOf("attempt" to runAttemptCount.toString()))
        val credentials = app.credentialStore.load()
        if (credentials == null) {
            app.diagnostics.log("background_sync_skipped", mapOf("reason" to "credentials_missing"))
            return Result.success()
        }
        if (!app.engine.state.value.credentialsConfigured) {
            app.engine.dispatch(Command.ConfigureCredentials(credentials.email, credentials.password))
        }

        val scheduleResult = app.engine.dispatch(Command.RefreshSchedule())
        if (scheduleResult is CommandResult.Rejected) return failureResult(app, scheduleResult.error)
        app.autoAttendanceScheduler.schedule(app.engine.state.value.scheduleSessions, app.engine.state.value.now)

        val state = app.engine.state.value
        val nearClass = state.scheduleSessions.any { session ->
            val windowStart = session.start.minus(CLASS_GUARD)
            val windowEnd = session.end.plus(CLASS_GUARD)
            state.now >= windowStart && state.now <= windowEnd
        }
        if (state.monitor.active || nearClass) {
            app.diagnostics.log("background_reading_sync_deferred", mapOf("reason" to "class_window"))
            return Result.retry()
        }

        val readingResult = app.engine.dispatch(Command.RefreshReadings)
        if (readingResult is CommandResult.Rejected) return failureResult(app, readingResult.error)
        app.diagnostics.log("background_sync_finished")
        return Result.success()
    }

    private fun failureResult(app: CompanionApplication, error: EngineError): Result {
        val terminal = error is EngineError.AuthenticationFailed
        app.diagnostics.log(
            "background_sync_failed",
            mapOf(
                "error" to error.javaClass.simpleName,
                "retry" to (!terminal).toString(),
            ),
        )
        return if (terminal) Result.failure() else Result.retry()
    }

    companion object {
        private const val UNIQUE_WORK = "companion-safe-listing-sync"
        private val CLASS_GUARD: Duration = Duration.ofMinutes(30)

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<CompanionSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
