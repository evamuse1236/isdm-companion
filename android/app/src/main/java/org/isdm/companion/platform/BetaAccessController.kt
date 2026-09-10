package org.isdm.companion.platform

import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.isdm.companion.engine.CompanionAccessException
import org.isdm.companion.engine.CompanionAccessPort

enum class BetaAccessStatus { ALLOWED, SUSPENDED, NEEDS_CONNECTION, INVALID_INSTALLATION, UPDATE_REQUIRED, NOT_ENROLLED }

data class BetaAccessSnapshot(
    val status: BetaAccessStatus,
    val checkedAt: Instant? = null,
    val validUntil: Instant? = null,
) {
    fun at(now: Instant): BetaAccessSnapshot = if (status == BetaAccessStatus.ALLOWED &&
        (checkedAt == null || validUntil == null || now.isBefore(checkedAt) || !now.isBefore(validUntil))) {
        copy(status = BetaAccessStatus.NEEDS_CONNECTION)
    } else this
}

interface BetaAccessStore {
    fun load(): BetaAccessSnapshot?
    fun save(snapshot: BetaAccessSnapshot)
}

/** One coalesced access check for foreground, workers, and the LMS adapter. */
class BetaAccessController(
    private val installation: () -> BetaInstallation?,
    private val fetch: suspend (BetaInstallation) -> BetaAccessSnapshot,
    private val store: BetaAccessStore,
    private val now: () -> Instant = Instant::now,
) : CompanionAccessPort {
    private val mutex = Mutex()
    private var lastAttempt: Instant? = null
    private var lastAttemptOnline = false
    private val _state = MutableStateFlow(
        if (installation() == null) BetaAccessSnapshot(BetaAccessStatus.NOT_ENROLLED)
        else store.load()?.at(now()) ?: BetaAccessSnapshot(BetaAccessStatus.NEEDS_CONNECTION),
    )
    val state = _state.asStateFlow()

    suspend fun refresh(): BetaAccessSnapshot = mutex.withLock { check(force = true).first }

    override suspend fun requireAccess(fresh: Boolean, allowEnrollment: Boolean) {
        mutex.withLock {
            if (installation() == null && allowEnrollment) return
            val (snapshot, online) = check(force = fresh)
            if (snapshot.status != BetaAccessStatus.ALLOWED || (fresh && !online)) {
                val reason = if (snapshot.status == BetaAccessStatus.ALLOWED) BetaAccessStatus.NEEDS_CONNECTION else snapshot.status
                throw CompanionAccessException(reason.name.lowercase())
            }
        }
    }

    private suspend fun check(force: Boolean): Pair<BetaAccessSnapshot, Boolean> {
        val current = installation() ?: return publish(BetaAccessSnapshot(BetaAccessStatus.NOT_ENROLLED)) to false
        val time = now()
        val cached = _state.value.at(time)
        val previousAttempt = lastAttempt
        if (!force && previousAttempt != null && !time.isBefore(previousAttempt) &&
            Duration.between(previousAttempt, time) < Duration.ofMinutes(1)) return publish(cached) to lastAttemptOnline
        if (!force && cached.status == BetaAccessStatus.ALLOWED && cached.checkedAt != null &&
            Duration.between(cached.checkedAt, time) < Duration.ofMinutes(1)) return cached to true
        lastAttempt = time
        lastAttemptOnline = false
        try {
            val received = fetch(current)
            // Bind the result to the token that was checked, including invite reclaim races.
            if (installation() != current) return publish(BetaAccessSnapshot(BetaAccessStatus.NEEDS_CONNECTION)) to false
            val bounded = received.copy(
                checkedAt = time,
                validUntil = received.validUntil?.coerceAtMost(time.plus(Duration.ofHours(6))),
            ).at(time)
            store.save(bounded)
            lastAttemptOnline = true
            return publish(bounded) to true
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            val status = when {
                error is BetaApiException && error.code == "access_suspended" -> BetaAccessStatus.SUSPENDED
                error is BetaApiException && error.statusCode == 401 -> BetaAccessStatus.INVALID_INSTALLATION
                else -> null
            }
            val fallback = status?.let { BetaAccessSnapshot(it, time) } ?: cached
            if (status != null) store.save(fallback)
            return publish(fallback) to false
        }
    }

    private fun publish(snapshot: BetaAccessSnapshot): BetaAccessSnapshot {
        _state.value = snapshot
        return snapshot
    }
}
