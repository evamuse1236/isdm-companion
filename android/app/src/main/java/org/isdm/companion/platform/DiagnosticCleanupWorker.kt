package org.isdm.companion.platform

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.isdm.companion.CompanionApplication
import java.util.concurrent.TimeUnit

/** Local maintenance works offline and remains independent of LMS/attendance sync. */
class DiagnosticCleanupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        (applicationContext as CompanionApplication).localDiagnostics.prune()
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "companion-local-log-cleanup", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DiagnosticCleanupWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
