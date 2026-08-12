package org.isdm.companion.platform

import android.content.Context
import android.util.Log
import org.isdm.companion.engine.DiagnosticsLogger
import java.io.File
import java.time.Instant
import java.util.UUID

class AndroidDiagnosticsLogger(context: Context) : DiagnosticsLogger {
    private val directory = File(context.filesDir, "diagnostics")
    private val current = File(directory, "companion.log")
    private val previous = File(directory, "companion.log.1")
    private val lock = Any()
    private val runId = UUID.randomUUID().toString().take(8)
    private var sequence = 0L

    override fun log(event: String, attributes: Map<String, String>, error: Throwable?) {
        val line = synchronized(lock) {
            val fields = buildList {
                sequence += 1
                add(Instant.now().toString())
                add("run=$runId")
                add("seq=$sequence")
                add("thread=${clean(Thread.currentThread().name)}")
                add(clean(event))
                attributes.toSortedMap().forEach { (key, value) -> add("${clean(key)}=${clean(value)}") }
                error?.let {
                    add("error=${clean(it::class.java.simpleName)}")
                    it.message?.takeIf(String::isNotBlank)?.let { message -> add("message=${clean(message)}") }
                    it.cause?.takeIf { cause -> cause !== it }?.let { cause ->
                        add("cause=${clean(cause::class.java.simpleName)}")
                        cause.message?.takeIf(String::isNotBlank)?.let { message ->
                            add("cause_message=${clean(message)}")
                        }
                    }
                }
            }
            fields.joinToString(" ").also { record ->
                runCatching {
                    directory.mkdirs()
                    rotateIfNeeded(record)
                    current.appendText("$record\n")
                }.onFailure { Log.w(TAG, "diagnostic_file_write_failed", it) }
            }
        }
        Log.i(TAG, line)
    }

    private fun rotateIfNeeded(nextLine: String) {
        if (current.exists() && current.length() + nextLine.length + 1 > MAX_BYTES) {
            previous.delete()
            current.renameTo(previous)
        }
    }

    private fun clean(value: String): String = sanitizeDiagnosticValue(value)
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_FIELD_LENGTH)

    private companion object {
        const val TAG = "ISDMCompanion"
        const val MAX_BYTES = 512 * 1024L
        const val MAX_FIELD_LENGTH = 240
    }
}

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
