package org.isdm.companion.domain

import java.util.Locale

private val DEFAULT_FLOORS: Map<String, Double> = mapOf(
    "sahyog" to 3.0,
    "majlis" to 6.0,
)

private fun normaliseRoom(room: String?): String = room.orEmpty().trim().lowercase(Locale.ROOT)

/** Parse room-floor overrides while retaining the built-in campus defaults. */
fun parseRoomFloors(spec: String?): Map<String, Double> {
    val floors = DEFAULT_FLOORS.toMutableMap()
    for (entry in spec.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
        val at = entry.lastIndexOf(':')
        if (at < 0) continue

        val room = normaliseRoom(entry.substring(0, at))
        val rawFloor = entry.substring(at + 1).trim()
        // A blank floor means ground floor. Non-finite values are ignored.
        val floor = if (rawFloor.isEmpty()) 0.0 else rawFloor.toDoubleOrNull()
        if (room.isNotEmpty() && floor != null && floor.isFinite()) floors[room] = floor
    }
    return floors
}

/** Return the configured floor, or null when the room is unknown. */
fun floorFor(room: String?, floors: Map<String, Double> = DEFAULT_FLOORS): Double? =
    floors[normaliseRoom(room)]?.takeIf { it.isFinite() }

/** Return the display label used by the dashboard. */
fun floorLabel(room: String?, floors: Map<String, Double> = DEFAULT_FLOORS): String? {
    val floor = floorFor(room, floors) ?: return null
    if (floor == 0.0) return "Ground floor"
    return "Floor ${jsNumberString(floor)}"
}

private fun jsNumberString(value: Double): String {
    if (value == 0.0) return "0"
    return if (value.isFinite() && value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        value.toString()
    }
}
