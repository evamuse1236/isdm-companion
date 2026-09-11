package org.isdm.companion.platform

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RollingDiagnosticStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private var time = Instant.parse("2026-09-01T12:00:00Z")
    private fun store(directory: File = temporary.root) = RollingDiagnosticStore(directory, now = { time })

    @Test fun `keeps exact seven day boundary and recycles earlier lines within same day`() {
        val store = store()
        store.append("${time.minusSeconds(1)} expired")
        store.append("$time boundary")
        time = time.plus(Duration.ofDays(7))
        store.append("$time newest")
        val snapshot = store.snapshot()
        assertFalse(snapshot.contains("expired"))
        assertTrue(snapshot.contains("boundary"))
        assertTrue(snapshot.contains("newest"))
        assertFalse(temporary.root.listFiles()!!.any { it.readText().contains("expired") })
    }

    @Test fun `cleanup after an idle week survives process restart`() {
        store().append("$time old_event")
        time = time.plus(Duration.ofDays(8))
        store().prune()
        assertEquals(0, temporary.root.listFiles()!!.size)
        store().append("$time fresh_event")
        assertTrue(store().snapshot().contains("fresh_event"))
    }

    @Test fun `size limit is measured in utf8 bytes and keeps newest complete records`() {
        val store = RollingDiagnosticStore(temporary.root, { time }, segmentBytes = 120, totalBytes = 240)
        repeat(20) { index -> store.append("$time event_$index ${"界".repeat(20)}") }
        val files = temporary.root.listFiles()!!
        assertTrue(files.sumOf(File::length) <= 240)
        assertTrue(store.snapshot().contains("event_19"))
        assertFalse(store.snapshot().contains("event_0 "))
        files.forEach { assertTrue(it.readText().endsWith("\n")) }
    }

    @Test fun `concurrent writers preserve whole records`() {
        val store = store()
        val executor = Executors.newFixedThreadPool(4)
        repeat(200) { index -> executor.submit { store.append("$time event_$index") } }
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        val records = store.snapshot().lineSequence().filter { it.startsWith(time.toString()) }.toList()
        assertEquals(200, records.size)
        assertEquals(200, records.toSet().size)
    }

    @Test fun `export excludes future timestamps after a clock correction`() {
        store().append("$time future_event")
        time = time.minus(Duration.ofDays(1))
        assertFalse(store().snapshot().contains("future_event"))
    }

    @Test fun `legacy logs and interrupted cleanup files do not escape retention`() {
        File(temporary.root, "companion.log").writeText("legacy credentials")
        File(temporary.root, "companion.log.1").writeText("old legacy credentials")
        File(temporary.root, "2026-09-01-000000.log.pruning").writeText("partial cleanup")
        store().prune()
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }
}
