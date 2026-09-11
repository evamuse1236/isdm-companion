package org.isdm.companion.platform

import android.content.Context
import android.util.Log
import android.os.Build
import org.isdm.companion.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.isdm.companion.engine.DiagnosticsLogger
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** A bounded queue keeps routine file I/O off the UI and attendance threads. */
class AndroidDiagnosticsLogger(context: Context) : DiagnosticsLogger {
    private val store = RollingDiagnosticStore(File(context.filesDir, "diagnostics"))
    private val runId = UUID.randomUUID().toString().take(8)
    private val sequence = AtomicLong()
    private val dropped = AtomicLong()
    private val queue = Channel<Work>(capacity = 256)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { store.prune() }
            for (work in queue) {
                when (work) {
                    is Work.Record -> runCatching {
                        val skipped = dropped.getAndSet(0)
                        if (skipped > 0) store.append(record("diagnostic_queue_overflow", mapOf("dropped" to skipped.toString()), null))
                        store.append(work.line)
                    }.onFailure { Log.w(TAG, "diagnostic_file_write_failed") }
                    is Work.Snapshot -> work.result.complete(runCatching { store.snapshot() })
                    is Work.Prune -> work.result.complete(runCatching { store.prune() })
                }
            }
        }
    }

    override fun log(event: String, attributes: Map<String, String>, error: Throwable?) {
        val line = record(event, attributes, error)
        if (!queue.trySend(Work.Record(line)).isSuccess) dropped.incrementAndGet()
        Log.i(TAG, line)
    }

    suspend fun snapshot(): String {
        val result = CompletableDeferred<Result<String>>()
        queue.send(Work.Snapshot(result))
        return "App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), ${BuildConfig.BUILD_TYPE}; Android SDK ${Build.VERSION.SDK_INT}\n" +
            result.await().getOrThrow()
    }

    suspend fun prune() {
        val result = CompletableDeferred<Result<Unit>>()
        queue.send(Work.Prune(result))
        result.await().getOrThrow()
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                // An uncaught exception cannot depend on a queued coroutine surviving process exit.
                runCatching { store.append(record("uncaught_exception", emptyMap(), error)) }
            } finally {
                previous.uncaughtException(thread, error)
            }
        }
    }

    private fun record(event: String, attributes: Map<String, String>, error: Throwable?): String =
        formatDiagnosticRecord(Instant.now(), runId, sequence.incrementAndGet(), Thread.currentThread().name, event, attributes, error)

    private sealed interface Work {
        data class Record(val line: String) : Work
        data class Snapshot(val result: CompletableDeferred<Result<String>>) : Work
        data class Prune(val result: CompletableDeferred<Result<Unit>>) : Work
    }

    private companion object { const val TAG = "ISDMCompanion" }
}

internal fun formatDiagnosticRecord(
    time: Instant, run: String, sequence: Long, thread: String,
    event: String, attributes: Map<String, String>, error: Throwable?,
): String = buildList {
    add(time.toString())
    add("run=${cleanDiagnosticValue(run)}")
    add("seq=$sequence")
    add("thread=${cleanDiagnosticValue(thread)}")
    add(cleanDiagnosticValue(event))
    attributes.toSortedMap().entries.take(32).forEach { (key, value) ->
        // Only known operational fields enter the local log; newly added payload fields default to omitted.
        if (key in DIAGNOSTIC_FIELDS) add("$key=${cleanDiagnosticValue(value)}")
    }
    error?.let {
        add("error=${cleanDiagnosticValue(it.javaClass.name)}")
        // Exception messages can contain server payloads or credentials. Keep types and source frames only.
        add("frames=${diagnosticFrames(it)}")
        it.cause?.takeIf { cause -> cause !== it }?.let { cause ->
            add("cause=${cleanDiagnosticValue(cause.javaClass.name)}")
            add("cause_frames=${diagnosticFrames(cause)}")
        }
    }
}.joinToString(" ").take(RollingDiagnosticStore.MAX_LINE_CHARS)

private fun diagnosticFrames(error: Throwable): String = error.stackTrace.take(8)
    .joinToString(";") { "${it.className}.${it.methodName}:${it.lineNumber}" }.let(::cleanDiagnosticValue)

private fun cleanDiagnosticValue(value: String): String = sanitizeDiagnosticValue(value)
    .replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex("\\s+"), " ").trim().take(400)

private val DIAGNOSTIC_FIELDS = setOf(
    "action", "alarms", "android_sdk", "attempt", "attendance_sessions", "auto_attendance_enabled",
    "background_location", "background_location_granted", "build_type", "category", "command", "courses", "delay_minutes", "device_model",
    "dropped", "duration_ms", "elapsed_ms", "enabled", "endpoint", "error", "event", "exact_alarms", "exact_alarms_granted", "failure_type",
    "fgs_type", "granted", "http_status", "importance", "markable_sessions", "marked_sessions",
    "monitor_active", "monitor_attempts", "monitor_mode", "monitor_targets", "notifications", "outcome",
    "permission", "precise_location", "provider", "purpose", "readings", "reason", "remaining", "restored", "result",
    "retry", "saved_login", "schedule_sessions", "sessions", "source", "timeout_ms", "timestamp",
    "version_code", "version_name", "windows",
)

internal fun sanitizeDiagnosticValue(value: String): String = value
    .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "[url]")
    .replace(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE), "[email]")
    .replace(
        Regex("(?i)\\b(authorization|proxy-authorization)\\s*[:=]\\s*(?:bearer|basic)?\\s*[^,;\\s]+"),
        "\$1=[redacted]",
    )
    .replace(Regex("(?i)\\bbearer\\s+[^,;\\s]+"), "Bearer [redacted]")
    .replace(
        Regex(
            "(?i)\\b(password|passwd|cookie|set-cookie|authorization|token|access_token|" +
                "refresh_token|client_secret|signature|sig)\\b\\s*[:=]?\\s*[^,;\\s]+",
        ),
        "\$1=[redacted]",
    )
