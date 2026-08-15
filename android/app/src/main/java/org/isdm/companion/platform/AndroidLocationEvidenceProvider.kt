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
     * Requests one fresh fix from Android's best enabled provider. This is available on the app's
     * API 26 minimum and deliberately does not request permission itself.
     */
    suspend fun currentEvidence(timeout: Duration = CURRENT_FIX_TIMEOUT): LocationEvidence? {
        if (!hasLocationPermission()) return null
        val provider = runCatching {
            locationManager.getBestProvider(Criteria(), true)
        }.getOrNull() ?: return bestRecentLastKnownEvidence()

        return withTimeoutOrNull(timeout.toMillis()) {
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
        }.also { evidence -> if (evidence != null) lastObservedEvidence = evidence }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isRecent(evidence: LocationEvidence, now: Instant): Boolean {
        val age = Duration.between(evidence.observedAt, now)
        return !age.isNegative && age <= DEFAULT_MAXIMUM_EVIDENCE_AGE
    }
}

private val CURRENT_FIX_TIMEOUT: Duration = Duration.ofSeconds(10)

private fun Location.toLocationEvidence() = LocationEvidence(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracy.toDouble(),
    observedAt = Instant.ofEpochMilli(time),
    isMock = isFromMockProvider,
)
