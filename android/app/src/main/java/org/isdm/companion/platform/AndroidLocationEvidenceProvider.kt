package org.isdm.companion.platform

import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.isdm.companion.domain.DEFAULT_MAXIMUM_EVIDENCE_AGE
import org.isdm.companion.domain.LocationEvidence
import kotlin.coroutines.resume

/**
 * Android adapter for [LocationEvidence]. It returns null when location permission is absent so
 * callers can pass that missing evidence to the fail-closed Attendance Location Gate.
 */
class AndroidLocationEvidenceProvider(
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
    private val locationManager: LocationManager = context.getSystemService(LocationManager::class.java),
    private val diagnostics: org.isdm.companion.engine.DiagnosticsLogger = org.isdm.companion.engine.NoopDiagnosticsLogger,
) {
    private val appContext = context.applicationContext
    @Volatile
    private var lastObservedEvidence: LocationEvidence? = null

    fun latestEvidence(): LocationEvidence? = lastObservedEvidence

    /** The most accurate recent last-known sample, resolving an accuracy tie by recency. */
    fun bestRecentLastKnownEvidence(
        now: Instant = clock.instant(),
    ): LocationEvidence? {
        if (!hasLocationPermission()) return null

        return runCatching {
            locationManager.getProviders(true)
                .mapNotNull(locationManager::getLastKnownLocation)
                .map(Location::toLocationEvidence)
                .filter { evidence -> isRecent(evidence, now) }
                .minWithOrNull(
                    compareBy<LocationEvidence> { it.accuracyMeters }
                        .thenByDescending { it.observedAt },
                )
        }.getOrNull().also { evidence -> if (evidence != null) lastObservedEvidence = evidence }
    }

    /**
     * Requests one high-accuracy fix from Android's best enabled provider. A cold GPS fix can
     * legitimately take longer than ten seconds, so the default covers the measured Vivo delay.
     * If the request times out, providers are queried again because another app or the platform
     * may have refreshed the last-known sample while this request was waiting.
     */
    suspend fun currentEvidence(timeout: Duration = LOCATION_ACQUISITION_POLICY.timeout): LocationEvidence? {
        if (!hasLocationPermission()) return null
        val fallbackBeforeRequest = bestRecentLastKnownEvidence()
        val provider = runCatching {
            locationManager.getBestProvider(highAccuracyLocationCriteria(), true)
        }.getOrNull() ?: return fallbackBeforeRequest
        val startedAt = android.os.SystemClock.elapsedRealtime()

        val fresh = withTimeoutOrNull(timeout.toMillis()) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching { locationManager.removeUpdates(this) }
                        if (continuation.isActive) continuation.resume(location.toLocationEvidence())
                    }
                }
                continuation.invokeOnCancellation {
                    runCatching { locationManager.removeUpdates(listener) }
                }
                try {
                    locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (_: SecurityException) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        val evidence = fresh ?: bestRecentLastKnownEvidence() ?: fallbackBeforeRequest
        diagnostics.log(
            "location_evidence_acquired",
            mapOf(
                "elapsed_ms" to (android.os.SystemClock.elapsedRealtime() - startedAt).toString(),
                "outcome" to when {
                    fresh != null -> "fresh"
                    evidence != null -> "recent_cache"
                    else -> "unavailable"
                },
                "provider" to provider,
            ),
        )
        if (evidence != null) lastObservedEvidence = evidence
        return evidence
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isRecent(evidence: LocationEvidence, now: Instant): Boolean {
        val age = Duration.between(evidence.observedAt, now)
        return !age.isNegative && age <= DEFAULT_MAXIMUM_EVIDENCE_AGE
    }
}

internal data class LocationAcquisitionPolicy(
    val requireFineAccuracy: Boolean,
    val preferHighPower: Boolean,
    val timeout: Duration,
)

internal val LOCATION_ACQUISITION_POLICY = LocationAcquisitionPolicy(
    requireFineAccuracy = true,
    preferHighPower = true,
    timeout = Duration.ofSeconds(30),
)

internal fun highAccuracyLocationCriteria(): Criteria = Criteria().apply {
    if (LOCATION_ACQUISITION_POLICY.requireFineAccuracy) accuracy = Criteria.ACCURACY_FINE
    if (LOCATION_ACQUISITION_POLICY.preferHighPower) powerRequirement = Criteria.POWER_HIGH
}

private fun Location.toLocationEvidence() = LocationEvidence(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracy.toDouble(),
    observedAt = Instant.ofEpochMilli(time),
    isMock = isFromMockProvider,
)
