package org.isdm.companion.platform

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BetaEventStoreTest {
    @Test
    fun `events remain queued until their ids are acknowledged`() {
        val preferences = FakeBetaPreferences()
        val store = JsonBetaEventStore(preferences, maximumEvents = 3)
        val first = event("00000000-0000-4000-8000-000000000001")
        val second = event("00000000-0000-4000-8000-000000000002")

        store.enqueue(first)
        store.enqueue(second)
        assertEquals(listOf(first, second), store.peek(100))

        store.acknowledge(setOf(first.id))
        assertEquals(listOf(second), store.peek(100))
    }

    @Test
    fun `queue is bounded and keeps the newest events`() {
        val store = JsonBetaEventStore(FakeBetaPreferences(), maximumEvents = 2)
        store.enqueue(event("00000000-0000-4000-8000-000000000001"))
        store.enqueue(event("00000000-0000-4000-8000-000000000002"))
        store.enqueue(event("00000000-0000-4000-8000-000000000003"))

        val queued = store.peek(100)
        assertEquals(2, queued.size)
        assertTrue(queued.none { it.id.endsWith("1") })
    }

    private fun event(id: String) = BetaEvent(
        id = id,
        type = "test_event",
        occurredAt = Instant.parse("2026-08-15T10:00:00Z"),
        payload = mapOf("state" to "ok"),
    )
}

private class FakeBetaPreferences : BetaPreferences {
    private val values = mutableMapOf<String, String>()

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String): Boolean {
        values[key] = value
        return true
    }
}
