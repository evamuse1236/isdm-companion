package org.isdm.companion.platform

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

interface BetaPreferences {
    fun get(key: String): String?
    fun put(key: String, value: String): Boolean
}

class JsonBetaEventStore(
    private val preferences: BetaPreferences,
    private val maximumEvents: Int = 500,
) {
    private val lock = Any()

    fun enqueue(event: BetaEvent): Boolean = synchronized(lock) {
        val events = read().filterNot { it.id == event.id }.plus(event).takeLast(maximumEvents)
        write(events)
    }

    fun peek(limit: Int): List<BetaEvent> = synchronized(lock) { read().take(limit.coerceAtLeast(0)) }

    fun acknowledge(ids: Set<String>): Boolean = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized true
        write(read().filterNot { it.id in ids })
    }

    private fun read(): List<BetaEvent> {
        val array = runCatching { JSONArray(preferences.get(EVENTS_KEY) ?: "[]") }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val payloadJson = item.optJSONObject("payload") ?: JSONObject()
                val payload = buildMap {
                    payloadJson.keys().forEach { key -> put(key, payloadJson.optString(key)) }
                }
                runCatching {
                    BetaEvent(
                        id = item.getString("id"),
                        type = item.getString("type"),
                        occurredAt = Instant.parse(item.getString("occurred_at")),
                        payload = payload,
                    )
                }.getOrNull()?.let(::add)
            }
        }
    }

    private fun write(events: List<BetaEvent>): Boolean {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject()
                    .put("id", event.id)
                    .put("type", event.type)
                    .put("occurred_at", event.occurredAt.toString())
                    .put("payload", JSONObject(event.payload)),
            )
        }
        return preferences.put(EVENTS_KEY, array.toString())
    }

    private companion object {
        const val EVENTS_KEY = "events"
    }
}

data class BetaQueuedRequest(
    val id: String,
    val route: String,
    val body: Map<String, Any?>,
)

class JsonBetaRequestStore(
    private val preferences: BetaPreferences,
    private val maximumRequests: Int = 200,
) {
    private val lock = Any()

    fun enqueue(request: BetaQueuedRequest): Boolean = synchronized(lock) {
        val requests = read().filterNot { it.id == request.id }.plus(request).takeLast(maximumRequests)
        write(requests)
    }

    fun peek(limit: Int): List<BetaQueuedRequest> = synchronized(lock) { read().take(limit.coerceAtLeast(0)) }

    fun acknowledge(id: String): Boolean = synchronized(lock) { write(read().filterNot { it.id == id }) }

    private fun read(): List<BetaQueuedRequest> {
        val array = runCatching { JSONArray(preferences.get(REQUESTS_KEY) ?: "[]") }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val body = item.optJSONObject("body") ?: continue
                runCatching {
                    BetaQueuedRequest(
                        id = item.getString("id"),
                        route = item.getString("route"),
                        body = body.toMap(),
                    )
                }.getOrNull()?.let(::add)
            }
        }
    }

    private fun write(requests: List<BetaQueuedRequest>): Boolean {
        val array = JSONArray()
        requests.forEach { request ->
            array.put(
                JSONObject()
                    .put("id", request.id)
                    .put("route", request.route)
                    .put("body", JSONObject(request.body)),
            )
        }
        return preferences.put(REQUESTS_KEY, array.toString())
    }

    private companion object {
        const val REQUESTS_KEY = "requests"
    }
}

private fun JSONObject.toMap(): Map<String, Any?> = buildMap {
    keys().forEach { key -> put(key, unwrapJson(opt(key))) }
}

private fun unwrapJson(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONObject -> value.toMap()
    is JSONArray -> List(value.length()) { index -> unwrapJson(value.opt(index)) }
    else -> value
}
