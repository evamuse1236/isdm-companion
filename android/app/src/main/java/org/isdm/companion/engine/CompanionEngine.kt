package org.isdm.companion.engine

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.isdm.companion.domain.Session as DomainSession
import org.isdm.companion.domain.buildSessions
import org.isdm.companion.domain.detectCohorts
import org.isdm.companion.domain.floorFor
import org.isdm.companion.domain.floorLabel
import org.isdm.companion.domain.sessionState

/**
 * Deep application module for the Android client.
 *
 * UI, the monitor foreground service, and notification actions all cross this one seam. The
 * engine owns ordering, state transitions, safety guards, LMS revalidation, and deduplication;
 * platform callers only dispatch commands and render the resulting immutable state.
 */
class CompanionEngine(
    private val gateway: LmsGateway,
    private val clock: Clock = SystemClock,
    private val notifier: Notifier = NoopNotifier,
    private val cohortOverride: Cohorts? = null,
    private val roomFloors: Map<String, Double> = mapOf("sahyog" to 3.0, "majlis" to 6.0),
    private val lateAfterMinutes: Long = 10,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<CompanionState> = _state.asStateFlow()

    private var credentials: Credentials? = null
    private var identity: Identity? = null
    private var authenticated = false
    private val openNotified = mutableSetOf<String>()
    private val autoAttempts = mutableMapOf<String, Int>()
    private val successfulMarks = mutableSetOf<String>()
    private val marking = mutableSetOf<String>()
    private var dayCache: DayCache? = null
    private var cohortCache: CacheEntry<Cohorts>? = null
    private val detailCache = mutableMapOf<String, CacheEntry<ClassroomDetail>>()

    suspend fun dispatch(command: Command): CommandResult = mutex.withLock {
        reconcileSafety()
        when (command) {
            is Command.ConfigureCredentials -> configureCredentials(command)
            Command.RefreshToday -> refreshToday(allowMonitoringEffects = false)
            is Command.Mark -> mark(command.sessionId, automatic = false)
            is Command.ArmMonitoring -> armMonitoring(command)
            Command.DisarmMonitoring -> disarmMonitoring()
            Command.SystemLimitReached -> systemLimitReached()
            Command.MonitorTick -> monitorTick()
        }
    }

    private fun initialState(): CompanionState {
        val now = clock.now()
        return CompanionState(now = now, today = now.atZone(LMS_ZONE).toLocalDate())
    }

    private suspend fun configureCredentials(command: Command.ConfigureCredentials): CommandResult {
        val email = command.email.trim()
        if (email.isEmpty() || command.password.isEmpty()) {
            return reject(EngineError.InvalidCommand("Email and password are required."))
        }
        // The engine deliberately keeps this only in memory; the platform owns persistence.
        val wasMonitoring = currentMonitor().active
        credentials = Credentials(email, command.password)
        identity = null
        authenticated = false
        gateway.resetSession()
        openNotified.clear()
        autoAttempts.clear()
        successfulMarks.clear()
        clearCaches()
        _state.value = stateNow().copy(
            credentialsConfigured = true,
            identity = null,
            sessions = emptyList(),
            monitor = MonitoringStatus(reason = if (wasMonitoring) MonitoringStopReason.DISARMED else null),
            error = null,
            sync = SyncStatus(),
        )
        if (wasMonitoring) safeNotify(NotificationEvent.MonitoringStopped(MonitoringStopReason.DISARMED))
        return CommandResult.Completed(_state.value)
    }

    private suspend fun refreshToday(allowMonitoringEffects: Boolean): CommandResult {
        val saved = credentials
        if (saved == null) return reject(EngineError.CredentialsMissing)

        val now = clock.now()
        val today = now.atZone(LMS_ZONE).toLocalDate()
        _state.value = stateNow().copy(
            today = today,
            sync = _state.value.sync.copy(inProgress = true, error = null),
            error = null,
        )

        return try {
            if (!authenticated) {
                identity = gateway.login(saved)
                authenticated = true
            }

            val tomorrow = today.plusDays(1)
            val (todayEvents, cohorts) = coroutineScope {
                val todayRequest = async { loadTodayEvents(today, tomorrow, now) }
                val cohortRequest = async { loadCohorts(today, now) }
                todayRequest.await() to cohortRequest.await()
            }

            val drafts = buildSessions(todayEvents, cohorts)
            var markabilityError: EngineError? = null
            val markMap = if (drafts.any { it.nid != null }) {
                try {
                    gateway.markability()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    markabilityError = mapError(error)
                    emptyMap()
                }
            } else emptyMap()
            val details = loadDetails(drafts, now)
            val assembled = drafts.map { draft ->
                val detail = draft.nid?.let(details::get)
                val marked = detail?.marked ?: false
                val markable = draft.nid?.let { markMap[it]?.markable == true } == true && !marked
                val room = detail?.room
                val base = draft.copy(
                    name = detail?.title?.takeIf { it.isNotBlank() } ?: draft.name,
                    subject = detail?.course ?: draft.subject,
                    trainer = detail?.trainer ?: draft.trainer,
                    room = room,
                    floor = floorFor(room, roomFloors),
                    floorLabel = floorLabel(room, roomFloors),
                    marked = marked,
                    markable = markable,
                    status = detail?.status,
                )
                CompanionSession(
                    nid = base.nid,
                    eventNid = base.eventNid,
                    name = base.name,
                    cohort = base.cohort,
                    sessionNumber = base.session,
                    start = base.start,
                    end = base.end ?: base.start,
                    subject = base.subject,
                    trainer = base.trainer,
                    room = base.room,
                    floor = base.floor,
                    floorLabel = base.floorLabel,
                    marked = base.marked,
                    markable = base.markable,
                    state = sessionState(base, now),
                    lateAfter = base.nid?.let {
                        base.start.plus(lateAfterMinutes, ChronoUnit.MINUTES)
                    },
                )
            }

            _state.value = stateNow().copy(
                today = today,
                credentialsConfigured = true,
                identity = identity,
                sessions = assembled,
                sync = SyncStatus(inProgress = false, lastSuccess = now, error = markabilityError),
                error = markabilityError,
            )

            if (allowMonitoringEffects) processMonitoringEffects()
            CommandResult.Completed(_state.value)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            authenticated = false
            val mapped = mapError(error)
            _state.value = stateNow().copy(
                credentialsConfigured = true,
                identity = identity,
                sync = SyncStatus(inProgress = false, error = mapped),
                error = mapped,
            )
            CommandResult.Rejected(mapped, _state.value)
        }
    }

    private suspend fun loadTodayEvents(
        today: LocalDate,
        tomorrow: LocalDate,
        now: Instant,
    ): List<CalendarEvent> {
        val cached = dayCache
        if (cached?.date == today && now.isBefore(cached.loadedAt.plusSeconds(LIVE_CALENDAR_TTL_SECONDS))) {
            return cached.events
        }
        return gateway.calendar(today, tomorrow).also {
            dayCache = DayCache(today, now, it)
        }
    }

    private suspend fun loadCohorts(today: LocalDate, now: Instant): Cohorts {
        cohortOverride?.let { return it }
        val cached = cohortCache
        if (cached != null && now.isBefore(cached.loadedAt.plusSeconds(COHORT_TTL_SECONDS))) {
            return cached.value
        }
        val events = gateway.calendar(today.minusDays(120), today.plusDays(120))
        return detectCohorts(events).also { cohortCache = CacheEntry(now, it) }
    }

    private suspend fun loadDetails(
        drafts: List<DomainSession>,
        now: Instant,
    ): Map<String, ClassroomDetail> {
        val withNid = drafts.mapNotNull { it.nid }.distinct()
        if (withNid.isEmpty()) return emptyMap()
        val semaphore = Semaphore(5)
        return coroutineScope {
            withNid.map { nid ->
                async {
                    semaphore.withPermit {
                        val cached = detailCache[nid]
                        val ttl = if (cached?.value?.marked == true) {
                            MARKED_DETAIL_TTL_SECONDS
                        } else {
                            LIVE_DETAIL_TTL_SECONDS
                        }
                        if (cached != null && now.isBefore(cached.loadedAt.plusSeconds(ttl))) {
                            return@withPermit nid to cached.value
                        }
                        try {
                            val detail = gateway.classroom(nid)
                            detailCache[nid] = CacheEntry(now, detail)
                            nid to detail
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    private suspend fun monitorTick(): CommandResult {
        if (!currentMonitor().active) return CommandResult.Completed(stateNow())
        val result = refreshToday(allowMonitoringEffects = true)
        return result
    }

    private suspend fun processMonitoringEffects() {
        val status = currentMonitor()
        if (!status.active) return
        for (session in _state.value.sessions) {
            if (session.nid == null || session.marked || session.state != SessionState.OPEN) continue
            val nid = session.nid
            if (status.mode == MonitoringMode.AUTO_MARK) {
                val attempts = autoAttempts[nid] ?: 0
                if (attempts < MAX_AUTO_ATTEMPTS && nid !in successfulMarks) {
                    autoAttempts[nid] = attempts + 1
                    val result = mark(nid, automatic = true)
                    continue
                }
            }
            if (openNotified.add(nid)) safeNotify(NotificationEvent.ClassOpen(session))
        }
        publishMonitorAttempts()
    }

    private suspend fun armMonitoring(command: Command.ArmMonitoring): CommandResult {
        if (credentials == null) return reject(EngineError.CredentialsMissing)
        val now = clock.now()
        val local = now.atZone(LMS_ZONE)
        val stopAt = command.stopAt?.atDate(local.toLocalDate())?.atZone(LMS_ZONE)?.toInstant()
        if (stopAt != null && !stopAt.isAfter(now)) {
            return reject(EngineError.InvalidCommand("Monitoring stop time must be later today."))
        }

        openNotified.clear()
        autoAttempts.clear()
        successfulMarks.clear()
        _state.value = stateNow().copy(
            monitor = MonitoringStatus(
                active = true,
                mode = command.mode,
                armedDate = local.toLocalDate(),
                armedAt = now,
                stopAt = stopAt,
                reason = null,
                autoAttempts = emptyMap(),
            ),
            error = null,
        )
        safeNotify(NotificationEvent.MonitoringArmed(command.mode, local.toLocalDate(), stopAt))
        return CommandResult.Completed(_state.value)
    }

    private suspend fun disarmMonitoring(): CommandResult {
        val wasActive = currentMonitor().active
        _state.value = stateNow().copy(monitor = MonitoringStatus(reason = MonitoringStopReason.DISARMED))
        openNotified.clear()
        autoAttempts.clear()
        if (wasActive) safeNotify(NotificationEvent.MonitoringStopped(MonitoringStopReason.DISARMED))
        return CommandResult.Completed(_state.value)
    }

    private suspend fun systemLimitReached(): CommandResult {
        val wasActive = currentMonitor().active
        _state.value = stateNow().copy(
            monitor = MonitoringStatus(reason = MonitoringStopReason.SYSTEM_LIMIT),
            error = EngineError.SystemLimitReached,
        )
        openNotified.clear()
        autoAttempts.clear()
        if (wasActive) safeNotify(NotificationEvent.MonitoringStopped(MonitoringStopReason.SYSTEM_LIMIT))
        return CommandResult.Completed(_state.value)
    }

    private suspend fun mark(sessionId: String, automatic: Boolean): CommandResult {
        val session = _state.value.sessions.firstOrNull { it.nid == sessionId }
            ?: return reject(EngineError.InvalidCommand("Unknown session: $sessionId"))
        if (session.marked || sessionId in successfulMarks) {
            return CommandResult.AlreadyMarked(sessionId, _state.value)
        }
        if (!marking.add(sessionId)) {
            return reject(EngineError.InvalidCommand("A mark for $sessionId is already in progress."))
        }

        try {
            val markMap = try {
                gateway.markability()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val mapped = mapError(error)
                _state.value = stateNow().copy(error = mapped, sync = _state.value.sync.copy(error = mapped))
                safeNotify(NotificationEvent.MarkFailed(session, mapped))
                return CommandResult.Rejected(mapped, _state.value)
            }
            if (markMap[sessionId]?.markable != true) {
                val error = EngineError.MarkWindowClosed(sessionId)
                _state.value = stateNow().copy(error = error)
                if (automatic) safeNotify(NotificationEvent.MarkFailed(session, error))
                return CommandResult.Rejected(error, _state.value)
            }

            val detail = try {
                gateway.markPresent(sessionId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val mapped = EngineError.MarkRejected(sessionId, error.message ?: "The LMS rejected the mark.")
                _state.value = stateNow().copy(error = mapped)
                if (automatic) safeNotify(NotificationEvent.MarkFailed(session, mapped))
                return CommandResult.Rejected(mapped, _state.value)
            }
            if (!detail.marked) {
                val error = EngineError.MarkRejected(
                    sessionId,
                    "The LMS accepted the request but still reports you as unmarked.",
                )
                _state.value = stateNow().copy(error = error)
                if (automatic) safeNotify(NotificationEvent.MarkFailed(session, error))
                return CommandResult.Rejected(error, _state.value)
            }

            successfulMarks += sessionId
            detailCache[sessionId] = CacheEntry(clock.now(), detail)
            val updated = session.copy(
                marked = true,
                markable = false,
                state = SessionState.MARKED,
                room = detail.room ?: session.room,
                floor = floorFor(detail.room ?: session.room, roomFloors),
                floorLabel = floorLabel(detail.room ?: session.room, roomFloors),
                trainer = detail.trainer ?: session.trainer,
                subject = detail.course ?: session.subject,
            )
            _state.value = stateNow().copy(
                sessions = _state.value.sessions.map { if (it.nid == sessionId) updated else it },
                error = null,
            )
            safeNotify(NotificationEvent.MarkedPresent(updated, automatic))
            return CommandResult.Marked(sessionId, automatic, _state.value)
        } finally {
            marking.remove(sessionId)
        }
    }

    private suspend fun reconcileSafety() {
        val monitor = currentMonitor()
        if (!monitor.active) {
            _state.value = stateNow()
            return
        }
        val now = clock.now()
        val today = now.atZone(LMS_ZONE).toLocalDate()
        val reason = when {
            monitor.armedDate != today -> MonitoringStopReason.NEW_DAY
            monitor.stopAt != null && !now.isBefore(monitor.stopAt) -> MonitoringStopReason.STOP_TIME
            else -> null
        }
        if (reason != null) {
            _state.value = stateNow().copy(monitor = MonitoringStatus(reason = reason))
            openNotified.clear()
            autoAttempts.clear()
            safeNotify(NotificationEvent.MonitoringStopped(reason))
        } else {
            _state.value = stateNow()
        }
    }

    private fun publishMonitorAttempts() {
        val monitor = currentMonitor()
        _state.value = _state.value.copy(monitor = monitor.copy(autoAttempts = autoAttempts.toMap()))
    }

    private fun currentMonitor(): MonitoringStatus = _state.value.monitor

    private fun stateNow(): CompanionState {
        val now = clock.now()
        return _state.value.copy(
            now = now,
            today = now.atZone(LMS_ZONE).toLocalDate(),
            monitor = _state.value.monitor.copy(autoAttempts = autoAttempts.toMap()),
        )
    }

    private fun reject(error: EngineError): CommandResult {
        _state.value = stateNow().copy(error = error)
        return CommandResult.Rejected(error, _state.value)
    }

    private suspend fun safeNotify(event: NotificationEvent) {
        try {
            notifier.notify(event)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Notifications must never turn a confirmed LMS mark into an application failure.
        }
    }

    private fun mapError(error: Throwable): EngineError {
        val message = error.message?.takeIf { it.isNotBlank() } ?: "The LMS request failed."
        val lower = message.lowercase()
        return when {
            "login" in lower || "password" in lower || "logged out" in lower || "auth" in lower ->
                EngineError.AuthenticationFailed(message)
            error is java.io.IOException || "network" in lower || "fetch" in lower || "timeout" in lower ->
                EngineError.NetworkFailure(message)
            else -> EngineError.LmsFailure(message)
        }
    }

    private fun clearCaches() {
        dayCache = null
        cohortCache = null
        detailCache.clear()
    }

    private data class CacheEntry<T>(val loadedAt: Instant, val value: T)

    private data class DayCache(
        val date: LocalDate,
        val loadedAt: Instant,
        val events: List<CalendarEvent>,
    )

    private companion object {
        const val MAX_AUTO_ATTEMPTS = 3
        const val LIVE_CALENDAR_TTL_SECONDS = 60L
        const val COHORT_TTL_SECONDS = 60L * 60L
        const val LIVE_DETAIL_TTL_SECONDS = 45L
        const val MARKED_DETAIL_TTL_SECONDS = 6L * 60L
    }
}
