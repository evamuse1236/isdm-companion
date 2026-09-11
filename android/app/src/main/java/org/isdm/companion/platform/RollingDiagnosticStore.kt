package org.isdm.companion.platform

import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Called on the diagnostics I/O worker; synchronized for the emergency crash writer. */
internal class RollingDiagnosticStore(
    private val directory: File,
    private val now: () -> Instant = Instant::now,
    private val segmentBytes: Long = 256 * 1024,
    private val totalBytes: Long = 4 * 1024 * 1024,
) {
    private var lastPruned: Instant? = null

    @Synchronized
    fun append(line: String) {
        val time = now()
        if (lastPruned == null || time < lastPruned || Duration.between(lastPruned, time) >= Duration.ofHours(1)) {
            prune()
        }
        check(directory.isDirectory || directory.mkdirs())
        val day = time.atOffset(ZoneOffset.UTC).toLocalDate().toString()
        val bytes = (line.take(MAX_LINE_CHARS).replace('\n', ' ').replace('\r', ' ') + "\n").toByteArray(Charsets.UTF_8)
        val today = files().filter { it.name.startsWith("$day-") }
        val latest = today.lastOrNull()
        val target = if (latest != null && latest.length() + bytes.size <= segmentBytes) latest else {
            val index = latest?.name?.removeSuffix(".log")?.substringAfterLast('-')?.toIntOrNull()?.plus(1) ?: 0
            File(directory, "$day-${index.toString().padStart(6, '0')}.log")
        }
        target.appendBytes(bytes)
        trimSize()
    }

    @Synchronized
    fun prune() {
        if (!directory.exists()) return
        val time = now()
        val cutoff = time.minus(RETENTION)
        val cutoffDay = cutoff.atOffset(ZoneOffset.UTC).toLocalDate().toString()
        // Old size-only logs cannot establish the new privacy/retention contract.
        listOf("companion.log", "companion.log.1").forEach { File(directory, it).delete() }
        directory.listFiles()?.filter { it.name.endsWith(".pruning") }?.forEach(File::delete)
        files().forEach { file ->
            val day = file.name.take(10)
            when {
                day < cutoffDay -> check(file.delete())
                day == cutoffDay || day > time.atOffset(ZoneOffset.UTC).toLocalDate().toString() -> {
                    // Trim the boundary day by event time, including timestamps made invalid by a clock change.
                    val retained = file.useLines { lines -> lines.filter { isRetained(it, cutoff, time) }.toList() }
                    if (retained.isEmpty()) check(file.delete()) else {
                        val replacement = File(directory, "${file.name}.pruning")
                        replacement.writeText(retained.joinToString("\n", postfix = "\n"))
                        check(replacement.renameTo(file))
                    }
                }
            }
        }
        trimSize()
        lastPruned = time
    }

    @Synchronized
    fun snapshot(): String {
        prune()
        val time = now()
        val cutoff = time.minus(RETENTION)
        return buildString {
            appendLine("ISDM Companion debug logs")
            appendLine("Exported: $time (UTC)")
            appendLine("Retention: last 7 days, up to 4 MiB; oldest entries may be recycled sooner at the size limit.")
            appendLine("This saved copy is outside the app's automatic cleanup.")
            appendLine()
            files().forEach { file -> file.useLines { lines ->
                lines.filter { isRetained(it, cutoff, time) }.forEach { appendLine(it) }
            } }
        }
    }

    private fun isRetained(line: String, cutoff: Instant, time: Instant): Boolean {
        val timestamp = runCatching { Instant.parse(line.substringBefore(' ')) }.getOrNull() ?: return false
        return timestamp >= cutoff && timestamp <= time
    }

    private fun trimSize() {
        val files = files()
        var bytes = files.sumOf(File::length)
        for (file in files) {
            if (bytes <= totalBytes) break
            val size = file.length()
            check(file.delete())
            bytes -= size
        }
    }

    private fun files(): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && FILE_NAME.matches(it.name) }.sortedBy(File::getName)

    companion object {
        val RETENTION: Duration = Duration.ofDays(7)
        const val MAX_LINE_CHARS = 4096
        private val FILE_NAME = Regex("\\d{4}-\\d{2}-\\d{2}-\\d{6}\\.log")
    }
}
