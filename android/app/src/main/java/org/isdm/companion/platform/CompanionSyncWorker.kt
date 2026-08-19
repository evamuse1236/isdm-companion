package org.isdm.companion.platform

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.isdm.companion.CompanionApplication
import org.isdm.companion.engine.Command
import org.isdm.companion.engine.CommandResult
import org.isdm.companion.engine.CompanionSession
import org.isdm.companion.engine.EngineError
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class CompanionSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CompanionApplication
        app.diagnostics.log("background_sync_started", mapOf("attempt" to runAttemptCount.toString()))
        if (runAttemptCount >= MAX_BACKGROUND_SYNC_ATTEMPTS) {
            app.diagnostics.log(
                "background_sync_retry_exhausted",
                mapOf("attempt" to runAttemptCount.toString()),
            )
            return Result.success()
        }
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
        app.autoAttendanceScheduler.schedule(app.engine.state.value.scheduleSessions, Instant.now())

        val state = app.engine.state.value
        val deferral = readingSyncDeferralDelay(state.scheduleSessions, state.monitor.active, Instant.now())
        if (deferral != null) {
            app.diagnostics.log(
                "background_reading_sync_deferred",
                mapOf("delay_minutes" to deferral.toMinutes().toString(), "reason" to "class_window"),
            )
            CompanionReadingSyncWorker.enqueue(applicationContext, deferral)
            app.diagnostics.log("background_sync_finished")
            return Result.success()
        }

        val readingResult = app.engine.dispatch(Command.RefreshReadings)
        if (readingResult is CommandResult.Rejected) return failureResult(app, readingResult.error)
        app.diagnostics.log("background_sync_finished")
        return Result.success()
    }

    private fun failureResult(app: CompanionApplication, error: EngineError): Result {
        val retry = shouldRetryBackgroundSync(error, runAttemptCount)
        app.diagnostics.log(
            "background_sync_failed",
            mapOf(
                "error" to error.javaClass.simpleName,
                "retry" to retry.toString(),
            ),
        )
        return when {
            retry -> Result.retry()
            error is EngineError.AuthenticationFailed -> Result.failure()
            else -> Result.success()
        }
    }

    companion object {
        private const val UNIQUE_WORK = "companion-safe-listing-sync"
        private const val IMMEDIATE_WORK = "companion-immediate-listing-sync"
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

        fun enqueueImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<CompanionSyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancelAll(context: Context): List<Operation> {
            val workManager = WorkManager.getInstance(context)
            return listOf(
                workManager.cancelUniqueWork(UNIQUE_WORK),
                workManager.cancelUniqueWork(IMMEDIATE_WORK),
                workManager.cancelUniqueWork(CompanionReadingSyncWorker.UNIQUE_WORK),
            )
        }
    }
}

class CompanionReadingSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CompanionApplication
        app.diagnostics.log("background_reading_sync_started", mapOf("attempt" to runAttemptCount.toString()))
        if (runAttemptCount >= MAX_BACKGROUND_SYNC_ATTEMPTS) {
            app.diagnostics.log(
                "background_reading_sync_retry_exhausted",
                mapOf("attempt" to runAttemptCount.toString()),
            )
            return Result.success()
        }
        val credentials = app.credentialStore.load()
        if (credentials == null) {
            app.diagnostics.log("background_reading_sync_skipped", mapOf("reason" to "credentials_missing"))
            return Result.success()
        }
        if (!app.engine.state.value.credentialsConfigured) {
            app.engine.dispatch(Command.ConfigureCredentials(credentials.email, credentials.password))
        }
        val state = app.engine.state.value
        val deferral = readingSyncDeferralDelay(state.scheduleSessions, state.monitor.active, Instant.now())
        if (deferral != null) {
            app.diagnostics.log(
                "background_reading_sync_deferred_again",
                mapOf("delay_minutes" to deferral.toMinutes().toString()),
            )
            enqueue(applicationContext, deferral, ExistingWorkPolicy.APPEND_OR_REPLACE)
            return Result.success()
        }
        val result = app.engine.dispatch(Command.RefreshReadings)
        if (result is CommandResult.Rejected) {
            val retry = shouldRetryBackgroundSync(result.error, runAttemptCount)
            app.diagnostics.log(
                "background_reading_sync_failed",
                mapOf("error" to result.error.javaClass.simpleName, "retry" to retry.toString()),
            )
            return when {
                retry -> Result.retry()
                result.error is EngineError.AuthenticationFailed -> Result.failure()
                else -> Result.success()
            }
        }
        app.diagnostics.log("background_reading_sync_finished")
        return Result.success()
    }

    companion object {
        internal const val UNIQUE_WORK = "companion-deferred-reading-sync"

        fun enqueue(
            context: Context,
            delay: Duration,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<CompanionReadingSyncWorker>()
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                policy,
                request,
            )
        }
    }
}

internal fun readingSyncDeferralDelay(
    sessions: List<CompanionSession>,
    monitorActive: Boolean,
    now: Instant,
): Duration? {
    val blockingUntil = sessions
        .filter { now >= it.start.minus(CLASS_GUARD) && now <= it.end.plus(CLASS_GUARD) }
        .maxOfOrNull { it.end.plus(CLASS_GUARD) }
    val resumeAt = blockingUntil ?: if (monitorActive) now.plus(CLASS_GUARD) else return null
    return Duration.between(now, resumeAt).coerceAtLeast(MINIMUM_READING_DEFERRAL)
}

private val CLASS_GUARD: Duration = Duration.ofMinutes(30)
private val MINIMUM_READING_DEFERRAL: Duration = Duration.ofMinutes(1)
private const val MAX_BACKGROUND_SYNC_ATTEMPTS = 3

internal fun shouldRetryBackgroundSync(error: EngineError, runAttemptCount: Int): Boolean =
    when (error) {
        is EngineError.AuthenticationFailed -> runAttemptCount == 0
        else -> runAttemptCount < MAX_BACKGROUND_SYNC_ATTEMPTS
    }
