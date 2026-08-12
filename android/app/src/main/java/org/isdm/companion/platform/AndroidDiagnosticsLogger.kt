package org.isdm.companion.platform

import android.content.Context
import android.util.Log
import org.isdm.companion.engine.DiagnosticsLogger
import java.io.File
import java.time.Instant

class AndroidDiagnosticsLogger(context: Context) : DiagnosticsLogger {
    private val directory = File(context.filesDir, "diagnostics")
    private val current = File(directory, "companion.log")
    private val previous = File(directory, "companion.log.1")
    private val lock = Any()

    override fun log(event: String, attributes: Map<String, String>, error: Throwable?) {
        val fields = buildList {
            add(Instant.now().toString())
            add(clean(event))
            attributes.toSortedMap().forEach { (key, value) -> add("${clean(key)}=${clean(value)}") }
            error?.let {
                add("error=${clean(it::class.java.simpleName)}")
                it.message?.takeIf(String::isNotBlank)?.let { message -> add("message=${clean(message)}") }
            }
        }
        val line = fields.joinToString(" ")
        Log.i(TAG, line)
        runCatching {
            synchronized(lock) {
                directory.mkdirs()
                rotateIfNeeded(line)
                current.appendText("$line\n")
            }
        }.onFailure { Log.w(TAG, "diagnostic_file_write_failed", it) }
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
        Regex("(?i)(password|passwd|cookie|authorization|token|signature|sig)=?\\s*[^,;\\s]+"),
        "\$1=[redacted]",
    )
