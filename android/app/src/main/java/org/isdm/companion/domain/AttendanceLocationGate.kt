package org.isdm.companion.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A fixed geographic area in which an attendance Mark action is allowed. */
data class CampusZone(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
)

/** A location sample with the precision and capture time needed to evaluate it safely. */
data class LocationEvidence(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val observedAt: Instant,
    val isMock: Boolean = false,
)

enum class LocationGateReason {
    MISSING_EVIDENCE,
    STALE_EVIDENCE,
    INACCURATE_EVIDENCE,
    MOCK_LOCATION_EVIDENCE,
    OUTSIDE_CAMPUS_ZONE,
}

data class AttendanceLocationGateDecision(
    val allowsMark: Boolean,
    val reason: LocationGateReason? = null,
)

/**
 * Fail-closed policy for the attendance Mark action. A Mark is permitted only when the latest
 * [LocationEvidence] is recent, sufficiently accurate, and inside the configured [CampusZone].
 */
class AttendanceLocationGate(
    private val campusZone: CampusZone = ISDM_CAMPUS_ZONE,
    private val maximumEvidenceAge: Duration = DEFAULT_MAXIMUM_EVIDENCE_AGE,
    private val maximumFutureEvidenceSkew: Duration = DEFAULT_MAXIMUM_FUTURE_EVIDENCE_SKEW,
) {
    fun evaluate(
        evidence: LocationEvidence?,
        now: Instant = Instant.now(),
    ): AttendanceLocationGateDecision {
        if (evidence == null) return denied(LocationGateReason.MISSING_EVIDENCE)

        val age = Duration.between(evidence.observedAt, now)
        if (age < maximumFutureEvidenceSkew.negated() || age > maximumEvidenceAge) {
            return denied(LocationGateReason.STALE_EVIDENCE)
        }
        if (!evidence.accuracyMeters.isFinite() || evidence.accuracyMeters < 0) {
            return denied(LocationGateReason.INACCURATE_EVIDENCE)
        }
        if (evidence.isMock) return denied(LocationGateReason.MOCK_LOCATION_EVIDENCE)
        if (!evidence.latitude.isFinite() || !evidence.longitude.isFinite()) {
            return denied(LocationGateReason.OUTSIDE_CAMPUS_ZONE)
        }
        val distanceToCampusCentre = haversineMeters(
            evidence.latitude,
            evidence.longitude,
            campusZone.latitude,
            campusZone.longitude,
        )
        if (distanceToCampusCentre > campusZone.radiusMeters) {
            return denied(LocationGateReason.OUTSIDE_CAMPUS_ZONE)
        }
        if (distanceToCampusCentre + evidence.accuracyMeters > campusZone.radiusMeters) {
            return denied(LocationGateReason.INACCURATE_EVIDENCE)
        }
        return AttendanceLocationGateDecision(allowsMark = true)
    }

    private fun denied(reason: LocationGateReason) = AttendanceLocationGateDecision(
        allowsMark = false,
        reason = reason,
    )
}

val ISDM_CAMPUS_ZONE = CampusZone(
    latitude = 28.61361395,
    longitude = 77.36098275,
    radiusMeters = 250.0,
)

private const val EARTH_RADIUS_METERS = 6_371_000.0
val DEFAULT_MAXIMUM_EVIDENCE_AGE: Duration = Duration.ofMinutes(2)
val DEFAULT_MAXIMUM_FUTURE_EVIDENCE_SKEW: Duration = Duration.ofSeconds(15)

private fun haversineMeters(
    firstLatitude: Double,
    firstLongitude: Double,
    secondLatitude: Double,
    secondLongitude: Double,
): Double {
    val latitudeDelta = Math.toRadians(secondLatitude - firstLatitude)
    val longitudeDelta = Math.toRadians(secondLongitude - firstLongitude)
    val firstLatitudeRadians = Math.toRadians(firstLatitude)
    val secondLatitudeRadians = Math.toRadians(secondLatitude)
    val haversine = sin(latitudeDelta / 2).let { it * it } +
        cos(firstLatitudeRadians) * cos(secondLatitudeRadians) * sin(longitudeDelta / 2).let { it * it }
    return EARTH_RADIUS_METERS * 2 * atan2(sqrt(haversine), sqrt(1 - haversine))
}
