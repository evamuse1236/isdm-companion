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
import org.isdm.companion.domain.AttendanceLocationGateDecision
import org.isdm.companion.domain.LocationGateReason
import org.isdm.companion.domain.buildSessions
import org.isdm.companion.domain.detectCohorts
import org.isdm.companion.domain.floorFor
import org.isdm.companion.domain.floorLabel
import org.isdm.companion.domain.resolveScheduleCohorts
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
    private val readingDoneStore: ReadingDoneStore = NoopReadingDoneStore,
    private val assessmentDoneStore: AssessmentDoneStore = NoopAssessmentDoneStore,
    private val cacheStore: CompanionCacheStore = NoopCompanionCacheStore,
    private val diagnostics: DiagnosticsLogger = NoopDiagnosticsLogger,
    private val attendanceTelemetry: AttendanceTelemetryPort = NoopAttendanceTelemetry,
    private val attendanceLocationGate: AttendanceLocationGatePort = AllowAttendanceLocationGate,
    private val cohortOverride: Cohorts? = null,
    private val cohortPreference: () -> Cohorts? = { null },
    private val roomFloors: Map<String, Double> = mapOf("sahyog" to 3.0, "majlis" to 6.0),
    private val lateAfterMinutes: Long = 10,
    private val facultyDirectory: FacultyDirectory? = null,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<CompanionState> = _state.asStateFlow()

    private var credentials: Credentials? = null
    private var identity: Identity? = null
    private var authenticated = false
    private val openNotified = mutableSetOf<String>()
    private val autoAttempts = mutableMapOf<String, Int>()
    private val autoFailureNotified = mutableSetOf<String>()
    private val autoTargetSessionIds = mutableSetOf<String>()
    private val successfulMarks = mutableSetOf<String>()
    private val marking = mutableSetOf<String>()
    private var dayCache: DayCache? = null
    private var cohortCache: CacheEntry<Cohorts>? = null
    private val detailCache = mutableMapOf<String, CacheEntry<ClassroomDetail>>()

    suspend fun dispatch(command: Command): CommandResult = mutex.withLock {
        val commandName = commandName(command)
        val startedAtNanos = System.nanoTime()
        diagnostics.log("command_started", mapOf("command" to commandName))
        try {
            reconcileSafety()
            val result = when (command) {
                is Command.ConfigureCredentials -> configureCredentials(command)
                Command.SignOut -> signOut()
                Command.RefreshToday -> refreshToday(allowMonitoringEffects = false)
                is Command.RefreshSchedule -> refreshSchedule(command.days)
                Command.RefreshReadings -> refreshReadings()
                Command.RefreshAttendance -> refreshAttendance()
                Command.RefreshAll -> refreshAll()
                is Command.ToggleReadingDone -> toggleReadingDone(command.readingId)
                is Command.SetAssessmentDone -> setAssessmentDone(command.assessmentId, command.done)
                is Command.Mark -> mark(command.sessionId, automatic = false)
                is Command.ArmMonitoring -> armMonitoring(command)
                Command.DisarmMonitoring -> disarmMonitoring()
                Command.SystemLimitReached -> systemLimitReached()
                Command.MonitorTick -> monitorTick()
            }
            val snapshot = _state.value
            diagnostics.log(
                "command_finished",
                mapOf(
                    "command" to commandName,
                    "result" to resultName(result),
                    "error" to result.errorName(),
                    "duration_ms" to ((System.nanoTime() - startedAtNanos) / 1_000_000L).toString(),
                    "sessions" to snapshot.sessions.size.toString(),
                    "schedule_sessions" to snapshot.scheduleSessions.size.toString(),
                    "marked_sessions" to snapshot.sessions.count(CompanionSession::marked).toString(),
                    "markable_sessions" to snapshot.sessions.count(CompanionSession::markable).toString(),
                    "monitor_active" to snapshot.monitor.active.toString(),
                    "monitor_mode" to (snapshot.monitor.mode?.name?.lowercase() ?: "none"),
                    "monitor_targets" to snapshot.monitor.targetSessionIds.size.toString(),
                    "monitor_attempts" to snapshot.monitor.autoAttempts.values.sum().toString(),
                ),
            )
            result
        } catch (error: CancellationException) {
            diagnostics.log(
                "command_cancelled",
                mapOf(
                    "command" to commandName,
                    "duration_ms" to ((System.nanoTime() - startedAtNanos) / 1_000_000L).toString(),
                ),
            )
            throw error
        } catch (error: Throwable) {
            diagnostics.log(
                "command_crashed",
                mapOf(
                    "command" to commandName,
                    "duration_ms" to ((System.nanoTime() - startedAtNanos) / 1_000_000L).toString(),
                ),
                error,
            )
            throw error
        }
    }

    private fun initialState(): CompanionState {
        val now = clock.now()
        val today = now.atZone(LMS_ZONE).toLocalDate()
        val cachedSchedule = cacheStore.loadSchedule()
        return CompanionState(
            now = now,
            today = today,
            scheduleStart = cachedSchedule?.start ?: today,
            scheduleEndExclusive = cachedSchedule?.endExclusive ?: today.plusDays(DEFAULT_SCHEDULE_DAYS.toLong()),
            scheduleSessions = cachedSchedule?.sessions.orEmpty(),
            readings = loadCachedReadings(),
            assessments = loadCachedAssessments(),
            facultyProfiles = cacheStore.loadFacultyProfiles(),
            scheduleSync = SyncStatus(lastSuccess = cachedSchedule?.syncedAt),
        )
    }

    private suspend fun configureCredentials(command: Command.ConfigureCredentials): CommandResult {
        val email = command.email.trim()
        if (email.isEmpty() || command.password.isEmpty()) {
            return reject(EngineError.InvalidCommand("Email and password are required."))
        }
        val configured = Credentials(email, command.password)
        if (credentials == configured) {
            _state.value = stateNow().copy(error = null)
            return CommandResult.Completed(_state.value)
        }
        // The engine deliberately keeps this only in memory; the platform owns persistence.
        val wasMonitoring = currentMonitor().active
        cacheStore.selectAccount(email)
        val cachedSchedule = cacheStore.loadSchedule()
        val today = clock.now().atZone(LMS_ZONE).toLocalDate()
        credentials = configured
        identity = null
        authenticated = false
        gateway.resetSession()
        openNotified.clear()
        autoAttempts.clear()
        autoFailureNotified.clear()
        autoTargetSessionIds.clear()
        successfulMarks.clear()
        clearCaches()
        _state.value = stateNow().copy(
            credentialsConfigured = true,
            identity = null,
            sessions = emptyList(),
            scheduleStart = cachedSchedule?.start ?: today,
            scheduleEndExclusive = cachedSchedule?.endExclusive
                ?: today.plusDays(DEFAULT_SCHEDULE_DAYS.toLong()),
            scheduleSessions = cachedSchedule?.sessions.orEmpty(),
            readings = loadCachedReadings(),
            assessments = loadCachedAssessments(),
            facultyProfiles = cacheStore.loadFacultyProfiles(),
            monitor = MonitoringStatus(reason = if (wasMonitoring) MonitoringStopReason.DISARMED else null),
            error = null,
            sync = SyncStatus(),
            scheduleSync = SyncStatus(lastSuccess = cachedSchedule?.syncedAt),
            readingSync = SyncStatus(),
            assessmentSync = SyncStatus(),
            attendanceSummary = null,
            attendanceSync = SyncStatus(),
        )
        if (wasMonitoring) safeNotify(NotificationEvent.MonitoringStopped(MonitoringStopReason.DISARMED))
        return CommandResult.Completed(_state.value)
    }

    private suspend fun signOut(): CommandResult {
        val wasMonitoring = currentMonitor().active
        credentials = null
        identity = null
        authenticated = false
        gateway.resetSession()
        openNotified.clear()
        autoAttempts.clear()
        autoFailureNotified.clear()
        autoTargetSessionIds.clear()
        successfulMarks.clear()
        marking.clear()
        clearCaches()
        val now = clock.now()
        val today = now.atZone(LMS_ZONE).toLocalDate()
        _state.value = CompanionState(now = now, today = today)
        if (wasMonitoring) safeNotify(NotificationEvent.MonitoringStopped(MonitoringStopReason.DISARMED))
        return CommandResult.Completed(_state.value)
    }

    private suspend fun refreshToday(
        allowMonitoringEffects: Boolean,
        includeMarkability: Boolean = true,
    ): CommandResult {
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
            authenticateIfNeeded(saved)

            val tomorrow = today.plusDays(1)
            val (todayEvents, cohorts) = coroutineScope {
                val todayRequest = async { loadTodayEvents(today, tomorrow, now) }
                val cohortRequest = async { loadCohorts(today, now) }
                todayRequest.await() to cohortRequest.await()
            }

            val drafts = buildSessions(todayEvents, cohorts)
            var markabilityError: EngineError? = null
            val markMap = if (!includeMarkability) {
                emptyMap()
            } else if (drafts.any { it.nid != null }) {
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
            val assembled = assembleSessions(drafts, details, markMap, now)
            val scheduleSessions = replaceScheduleDate(_state.value.scheduleSessions, today, assembled)

            _state.value = stateNow().copy(
                today = today,
                credentialsConfigured = true,
                identity = identity,
                detectedCohorts = cohorts,
                sessions = assembled,
                scheduleSessions = scheduleSessions,
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

    private suspend fun refreshAll(): CommandResult {
        val todayResult = refreshToday(allowMonitoringEffects = false)
        if (todayResult is CommandResult.Rejected) return todayResult
        refreshSchedule(DEFAULT_SCHEDULE_DAYS)
        refreshReadings()
        refreshAttendance()
        return CommandResult.Completed(_state.value)
    }

    private suspend fun refreshAttendance(): CommandResult {
        val saved = credentials ?: return reject(EngineError.CredentialsMissing)
        val now = clock.now()
        _state.value = stateNow().copy(
            attendanceSync = _state.value.attendanceSync.copy(inProgress = true, error = null),
        )
        return try {
            authenticateIfNeeded(saved)
            val summary = gateway.attendanceSummary()
            _state.value = stateNow().copy(
                attendanceSummary = summary,
                attendanceSync = SyncStatus(lastSuccess = now),
            )
            CommandResult.Completed(_state.value)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val mapped = mapError(error)
            _state.value = stateNow().copy(attendanceSync = SyncStatus(error = mapped))
            CommandResult.Rejected(mapped, _state.value)
        }
    }

    private suspend fun refreshSchedule(days: Int): CommandResult {
        if (days !in 1..MAX_SCHEDULE_DAYS) {
            return reject(EngineError.InvalidCommand("Schedule range must be between 1 and $MAX_SCHEDULE_DAYS days."))
        }
        val saved = credentials ?: return reject(EngineError.CredentialsMissing)
        val now = clock.now()
        val start = now.atZone(LMS_ZONE).toLocalDate()
        val end = start.plusDays(days.toLong())
        _state.value = stateNow().copy(scheduleSync = _state.value.scheduleSync.copy(inProgress = true, error = null))
        return try {
            authenticateIfNeeded(saved)
            val (events, cohorts) = coroutineScope {
                val eventsRequest = async { gateway.calendar(start, end) }
                val cohortRequest = async { loadCohorts(start, now) }
                eventsRequest.await() to cohortRequest.await()
            }
            val drafts = buildSessions(events, cohorts)
            val details = loadDetails(drafts, now)
            val assembled = assembleSessions(drafts, details, emptyMap(), now)
            val scheduleSessions = if (_state.value.sync.lastSuccess != null) {
                replaceScheduleDate(assembled, start, _state.value.sessions)
            } else {
                assembled
            }
            _state.value = stateNow().copy(
                scheduleStart = start,
                scheduleEndExclusive = end,
                detectedCohorts = cohorts,
                scheduleSessions = scheduleSessions,
                scheduleSync = SyncStatus(lastSuccess = now),
            )
            // Cache only listing state; live markability is transient and must be revalidated.
            cacheStore.saveSchedule(CachedSchedule(start, end, assembled, now))
            CommandResult.Completed(_state.value)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val mapped = mapError(error)
            _state.value = stateNow().copy(scheduleSync = SyncStatus(error = mapped))
            CommandResult.Rejected(mapped, _state.value)
        }
    }

    private suspend fun refreshReadings(): CommandResult {
        val saved = credentials ?: return reject(EngineError.CredentialsMissing)
        val now = clock.now()
        _state.value = stateNow().copy(
            readingSync = _state.value.readingSync.copy(inProgress = true, error = null),
            assessmentSync = _state.value.assessmentSync.copy(inProgress = true, error = null),
        )
        return try {
            authenticateIfNeeded(saved)
            val assessmentFetch = captureFetchWithRetry { gateway.assessments() }
            if (assessmentFetch.error == null) {
                val freshAssessments = assessmentFetch.value.orEmpty()
                    .distinctBy { it.id }
                val assessments = applyLocalAssessmentDone(freshAssessments)
                _state.value = stateNow().copy(
                    assessments = assessments,
                    assessmentSync = SyncStatus(lastSuccess = now),
                )
                cacheStore.saveAssessments(freshAssessments)
                diagnostics.log("assessments_refreshed", mapOf("assessments" to assessments.size.toString()))
            } else {
                val mapped = mapError(assessmentFetch.error)
                _state.value = stateNow().copy(assessmentSync = SyncStatus(error = mapped))
                diagnostics.log("assessments_refresh_failed", error = assessmentFetch.error)
            }
            val courses = gateway.courses()
            val semaphore = Semaphore(3)
            val courseContent = coroutineScope {
                courses.map { course ->
                    async {
                        semaphore.withPermit {
                            CourseContent(
                                readings = captureFetchWithRetry { gateway.readings(course) },
                                facultyProfiles = facultyDirectory?.let { directory ->
                                    captureFetchWithRetry { directory.facultyProfiles(course) }
                                },
                            )
                        }
                    }
                }.awaitAll()
            }

            val readingFailures = courseContent.mapNotNull { it.readings.error }
            val profileFetches = courseContent.mapNotNull { it.facultyProfiles }
            val profileFailures = profileFetches.mapNotNull { it.error }
            var updated = stateNow()

            if (readingFailures.isEmpty()) {
                val fresh = courseContent.flatMap { it.readings.value.orEmpty() }.distinctBy { it.vid }
                val done = readingDoneStore.load()
                updated = updated.copy(
                    readings = fresh.map { it.copy(done = it.vid in done) }.sortedBy { it.done },
                    readingSync = SyncStatus(lastSuccess = now),
                )
                cacheStore.saveReadings(fresh)
                diagnostics.log(
                    "readings_refreshed",
                    mapOf("courses" to courses.size.toString(), "readings" to fresh.size.toString()),
                )
            } else {
                diagnostics.log(
                    "readings_refresh_failed",
                    mapOf("courses" to courses.size.toString(), "failed_courses" to readingFailures.size.toString()),
                )
            }

            if (facultyDirectory != null && profileFailures.isEmpty()) {
                val profiles = profileFetches.flatMap { it.value.orEmpty() }
                    .distinctBy { it.courseCatId to it.sourceUrl }
                updated = updated.copy(facultyProfiles = profiles)
                cacheStore.saveFacultyProfiles(profiles)
                diagnostics.log(
                    "faculty_profiles_refreshed",
                    mapOf("courses" to courses.size.toString(), "profiles" to profiles.size.toString()),
                )
            } else if (profileFailures.isNotEmpty()) {
                diagnostics.log(
                    "faculty_profiles_refresh_failed",
                    mapOf("courses" to courses.size.toString(), "failed_courses" to profileFailures.size.toString()),
                )
            }

            _state.value = updated
            if (readingFailures.isEmpty()) {
                CommandResult.Completed(_state.value)
            } else {
                val mapped = mapError(readingFailures.first())
                _state.value = stateNow().copy(readingSync = SyncStatus(error = mapped))
                CommandResult.Rejected(mapped, _state.value)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val mapped = mapError(error)
            _state.value = stateNow().copy(
                readingSync = SyncStatus(error = mapped),
                assessmentSync = if (_state.value.assessmentSync.inProgress) SyncStatus(error = mapped) else _state.value.assessmentSync,
            )
            CommandResult.Rejected(mapped, _state.value)
        }
    }

    private fun toggleReadingDone(readingId: String): CommandResult {
        val reading = _state.value.readings.firstOrNull { it.vid == readingId }
            ?: return reject(EngineError.InvalidCommand("Unknown reading id: $readingId"))
        val done = !reading.done
        readingDoneStore.setDone(readingId, done)
        _state.value = stateNow().copy(
            readings = _state.value.readings
                .map { if (it.vid == readingId) it.copy(done = done) else it }
                .sortedBy { it.done },
            error = null,
        )
        return CommandResult.Completed(_state.value)
    }

    private fun setAssessmentDone(assessmentId: String, done: Boolean): CommandResult {
        val assessment = _state.value.assessments.firstOrNull { it.id == assessmentId }
            ?: return reject(EngineError.InvalidCommand("Unknown assessment id: $assessmentId"))
        if (assessment.done == done) return CommandResult.Completed(_state.value)
        assessmentDoneStore.setAssessmentDone(assessmentId, done)
        _state.value = stateNow().copy(
            assessments = _state.value.assessments
                .map { if (it.id == assessmentId) it.copy(done = done) else it }
                .sortedWith(ASSESSMENT_ORDER),
            error = null,
        )
        return CommandResult.Completed(_state.value)
    }

    private fun assembleSessions(
        drafts: List<DomainSession>,
        details: Map<String, ClassroomDetail>,
        markMap: Map<String, Markability>,
        now: Instant,
    ): List<CompanionSession> = drafts.map { draft ->
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
            end = detail?.end ?: draft.end,
            endEstimated = detail?.end == null && draft.endEstimated,
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
            lateAfter = base.nid?.let { base.start.plus(lateAfterMinutes, ChronoUnit.MINUTES) },
            endEstimated = base.endEstimated,
        )
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
        val detected = if (cached != null && now.isBefore(cached.loadedAt.plusSeconds(COHORT_TTL_SECONDS))) {
            cached.value
        } else {
            val events = gateway.calendar(today.minusDays(120), today.plusDays(120))
            detectCohorts(events).also { cohortCache = CacheEntry(now, it) }
        }
        return resolveScheduleCohorts(detected, cohortPreference())
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
        val result = refreshToday(
            allowMonitoringEffects = true,
            // Auto-mark's live markability check belongs only in mark(), after the location gate.
            includeMarkability = currentMonitor().mode != MonitoringMode.AUTO_MARK,
        )
        return result
    }

    private suspend fun processMonitoringEffects() {
        val status = currentMonitor()
        if (!status.active) return
        for (session in _state.value.sessions) {
            if (session.nid == null || session.marked) continue
            val nid = session.nid
            if (status.mode == MonitoringMode.AUTO_MARK) {
                if (status.targetSessionIds.isNotEmpty() && nid !in status.targetSessionIds) continue
                val now = clock.now()
                if (!isInsideAutoAttendanceWindow(session, now)) continue
                val attempts = autoAttempts[nid] ?: 0
                if (attempts < MAX_AUTO_ATTEMPTS && nid !in successfulMarks) {
                    val result = mark(nid, automatic = true)
                    if (result is CommandResult.Marked) {
                        if (!currentMonitor().active) return
                        continue
                    }
                    if (result is CommandResult.Rejected && result.error is EngineError.MarkRejected) {
                        autoAttempts[nid] = attempts + 1
                    }
                    continue
                }
            }
            if (session.state != SessionState.OPEN) continue
            if (openNotified.add(nid)) safeNotify(NotificationEvent.ClassOpen(session))
        }
        publishMonitorAttempts()
    }

    private fun isInsideAutoAttendanceWindow(session: CompanionSession, now: Instant): Boolean =
        !now.isBefore(session.start.minus(AUTO_ATTENDANCE_LEAD)) &&
            now.isBefore(session.end.plus(AUTO_ATTENDANCE_GRACE))

    private fun replaceScheduleDate(
        schedule: List<CompanionSession>,
        date: LocalDate,
        replacement: List<CompanionSession>,
    ): List<CompanionSession> = (schedule.filter { it.start.atZone(LMS_ZONE).toLocalDate() != date } + replacement)
        .sortedWith(compareBy<CompanionSession> { it.start }.thenBy { it.name })

    private suspend fun armMonitoring(command: Command.ArmMonitoring): CommandResult {
        if (credentials == null) return reject(EngineError.CredentialsMissing)
        val now = clock.now()
        val local = now.atZone(LMS_ZONE)
        val stopAt = command.stopAt?.atDate(local.toLocalDate())?.atZone(LMS_ZONE)?.toInstant()
        if (stopAt != null && !stopAt.isAfter(now)) {
            return reject(EngineError.InvalidCommand("Monitoring stop time must be later today."))
        }

        val targetSessionId = command.targetSessionId
        if (targetSessionId != null && _state.value.sessions.none { it.nid == targetSessionId }) {
            return reject(EngineError.InvalidCommand("Unknown attendance session: $targetSessionId"))
        }
        val existing = currentMonitor()
        if (existing.active && existing.mode == MonitoringMode.AUTO_MARK &&
            command.mode == MonitoringMode.AUTO_MARK && targetSessionId != null
        ) {
            autoTargetSessionIds += targetSessionId
            val combinedStopAt = listOfNotNull(existing.stopAt, stopAt).maxOrNull()
            _state.value = stateNow().copy(
                monitor = existing.copy(
                    stopAt = combinedStopAt,
                    targetSessionIds = autoTargetSessionIds.toSet(),
                ),
                error = null,
            )
            safeNotify(NotificationEvent.MonitoringArmed(command.mode, local.toLocalDate(), combinedStopAt))
            return CommandResult.Completed(_state.value)
        }

        openNotified.clear()
        autoAttempts.clear()
        autoFailureNotified.clear()
        autoTargetSessionIds.clear()
        targetSessionId?.let(autoTargetSessionIds::add)
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
                targetSessionIds = autoTargetSessionIds.toSet(),
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
        autoFailureNotified.clear()
        autoTargetSessionIds.clear()
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
        autoFailureNotified.clear()
        autoTargetSessionIds.clear()
        if (wasActive) safeNotify(NotificationEvent.MonitoringStopped(MonitoringStopReason.SYSTEM_LIMIT))
        return CommandResult.Completed(_state.value)
    }

    private suspend fun mark(sessionId: String, automatic: Boolean): CommandResult {
        val session = _state.value.sessions.firstOrNull { it.nid == sessionId }
            ?: return reject(EngineError.InvalidCommand("Unknown session: $sessionId"))
        if (session.marked || sessionId in successfulMarks) {
            safeRecordAttendance(
                AttendanceTelemetryEvent(
                    method = if (automatic) "auto" else "manual",
                    sessionId = sessionId,
                    sessionLabel = session.name,
                    outcome = "present",
                    result = "already_marked",
                    gateAllowed = null,
                    gateReason = null,
                    lmsMarkable = null,
                ),
            )
            return CommandResult.AlreadyMarked(sessionId, _state.value)
        }
        if (!marking.add(sessionId)) {
            return reject(EngineError.InvalidCommand("A mark for $sessionId is already in progress."))
        }

        var gateAllowed: Boolean? = null
        var gateReason: LocationGateReason? = null
        var lmsMarkable: Boolean? = null
        var telemetryOutcome = "unknown"
        var telemetryResult = "unknown"
        try {
            val locationDecision = try {
                attendanceLocationGate.evaluate(clock.now())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                AttendanceLocationGateDecision(
                    allowsMark = false,
                    reason = LocationGateReason.MISSING_EVIDENCE,
                )
            }
            gateAllowed = locationDecision.allowsMark
            gateReason = locationDecision.reason
            if (!locationDecision.allowsMark) {
                telemetryOutcome = "blocked"
                telemetryResult = "location_denied"
                val error = EngineError.AttendanceLocationDenied(
                    locationDecision.reason ?: LocationGateReason.MISSING_EVIDENCE,
                )
                _state.value = stateNow().copy(error = error)
                if (automatic) notifyAutomaticFailureOnce(session, error)
                return CommandResult.Rejected(error, _state.value)
            }

            val markMap = try {
                gateway.markability()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                telemetryOutcome = "failed"
                telemetryResult = "markability_failed"
                val mapped = mapError(error)
                _state.value = stateNow().copy(error = mapped, sync = _state.value.sync.copy(error = mapped))
                if (automatic) notifyAutomaticFailureOnce(session, mapped)
                else safeNotify(NotificationEvent.MarkFailed(session, mapped))
                return CommandResult.Rejected(mapped, _state.value)
            }
            lmsMarkable = markMap[sessionId]?.markable == true
            if (lmsMarkable != true) {
                telemetryOutcome = "blocked"
                telemetryResult = "not_markable"
                val error = EngineError.MarkWindowClosed(sessionId)
                _state.value = stateNow().copy(error = error)
                return CommandResult.Rejected(error, _state.value)
            }

            val detail = try {
                gateway.markPresent(sessionId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                telemetryOutcome = "failed"
                telemetryResult = "mark_failed"
                val mapped = EngineError.MarkRejected(sessionId, error.message ?: "The LMS rejected the mark.")
                _state.value = stateNow().copy(error = mapped)
                if (automatic) notifyAutomaticFailureOnce(session, mapped)
                return CommandResult.Rejected(mapped, _state.value)
            }
            if (!detail.marked) {
                telemetryOutcome = "failed"
                telemetryResult = "mark_not_confirmed"
                val error = EngineError.MarkRejected(
                    sessionId,
                    "The LMS accepted the request but still reports you as unmarked.",
                )
                _state.value = stateNow().copy(error = error)
                if (automatic) notifyAutomaticFailureOnce(session, error)
                return CommandResult.Rejected(error, _state.value)
            }

            successfulMarks += sessionId
            telemetryOutcome = "present"
            telemetryResult = "marked_present"
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
            val monitor = if (automatic && currentMonitor().mode == MonitoringMode.AUTO_MARK) {
                val hasExplicitTargets = currentMonitor().targetSessionIds.isNotEmpty()
                autoTargetSessionIds.remove(sessionId)
                autoAttempts.remove(sessionId)
                autoFailureNotified.remove(sessionId)
                if (hasExplicitTargets && autoTargetSessionIds.isNotEmpty()) {
                    currentMonitor().copy(
                        autoAttempts = autoAttempts.toMap(),
                        targetSessionIds = autoTargetSessionIds.toSet(),
                    )
                } else {
                    autoAttempts.clear()
                    autoTargetSessionIds.clear()
                    MonitoringStatus(reason = MonitoringStopReason.ATTENDANCE_MARKED)
                }
            } else {
                currentMonitor()
            }
            _state.value = stateNow().copy(
                sessions = _state.value.sessions.map { if (it.nid == sessionId) updated else it },
                scheduleSessions = _state.value.scheduleSessions.map {
                    if (it.nid == sessionId) updated else it
                },
                monitor = monitor,
                error = null,
            )
            safeNotify(NotificationEvent.MarkedPresent(updated, automatic))
            return CommandResult.Marked(sessionId, automatic, _state.value)
        } finally {
            safeRecordAttendance(
                AttendanceTelemetryEvent(
                    method = if (automatic) "auto" else "manual",
                    sessionId = sessionId,
                    sessionLabel = session.name,
                    outcome = telemetryOutcome,
                    result = telemetryResult,
                    gateAllowed = gateAllowed,
                    gateReason = gateReason,
                    lmsMarkable = lmsMarkable,
                ),
            )
            marking.remove(sessionId)
        }
    }

    private suspend fun safeRecordAttendance(event: AttendanceTelemetryEvent) {
        try {
            attendanceTelemetry.record(event)
        } catch (error: Throwable) {
            diagnostics.log("attendance_telemetry_failed", mapOf("outcome" to event.outcome), error)
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
            autoFailureNotified.clear()
            autoTargetSessionIds.clear()
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
        } catch (error: Throwable) {
            // Notifications must never turn a confirmed LMS mark into an application failure.
            diagnostics.log("notification_failed", mapOf("event" to event.javaClass.simpleName), error)
        }
    }

    private suspend fun notifyAutomaticFailureOnce(session: CompanionSession, error: EngineError) {
        val nid = session.nid ?: return
        if (autoFailureNotified.add(nid)) safeNotify(NotificationEvent.MarkFailed(session, error))
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

    private fun loadCachedReadings(): List<ReadingItem> {
        val done = readingDoneStore.load()
        return cacheStore.loadReadings()
            .map { it.copy(done = it.vid in done) }
            .sortedBy { it.done }
    }

    private fun loadCachedAssessments(): List<AssessmentItem> {
        return applyLocalAssessmentDone(cacheStore.loadAssessments())
    }

    private fun applyLocalAssessmentDone(assessments: List<AssessmentItem>): List<AssessmentItem> {
        val done = assessmentDoneStore.loadAssessmentDone().toMutableSet()
        assessments.filter { it.id in done && it.isLmsSubmitted() }.forEach { assessment ->
            assessmentDoneStore.setAssessmentDone(assessment.id, done = false)
            done -= assessment.id
        }
        return assessments
            .map { it.copy(done = it.id in done) }
            .sortedWith(ASSESSMENT_ORDER)
    }

    private suspend fun <T> captureFetchWithRetry(block: suspend () -> T): Fetch<T> {
        for (attempt in 1..MAX_COURSE_FETCH_ATTEMPTS) {
            try {
                return Fetch(value = block())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (attempt == MAX_COURSE_FETCH_ATTEMPTS || !isRetryableFetch(error)) {
                    return Fetch(error = error)
                }
            }
        }
        error("unreachable")
    }

    private fun isRetryableFetch(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase()
        return error is java.io.IOException || "network" in message || "fetch" in message || "timeout" in message
    }

    private suspend fun authenticateIfNeeded(saved: Credentials) {
        if (authenticated) return
        identity = gateway.login(saved)
        authenticated = true
    }

    private data class CacheEntry<T>(val loadedAt: Instant, val value: T)

    private data class CourseContent(
        val readings: Fetch<List<ReadingItem>>,
        val facultyProfiles: Fetch<List<FacultyProfile>>?,
    )

    private data class Fetch<T>(val value: T? = null, val error: Throwable? = null)

    private data class DayCache(
        val date: LocalDate,
        val loadedAt: Instant,
        val events: List<CalendarEvent>,
    )

    private companion object {
        const val DEFAULT_SCHEDULE_DAYS = 14
        const val MAX_COURSE_FETCH_ATTEMPTS = 2
        const val MAX_SCHEDULE_DAYS = 31
        const val MAX_AUTO_ATTEMPTS = 3
        const val LIVE_CALENDAR_TTL_SECONDS = 60L
        const val COHORT_TTL_SECONDS = 60L * 60L
        const val LIVE_DETAIL_TTL_SECONDS = 45L
        const val MARKED_DETAIL_TTL_SECONDS = 6L * 60L
    }
}

private fun commandName(command: Command): String = when (command) {
    is Command.ConfigureCredentials -> "configure_credentials"
    Command.SignOut -> "sign_out"
    Command.RefreshToday -> "refresh_today"
    is Command.RefreshSchedule -> "refresh_schedule"
    Command.RefreshReadings -> "refresh_readings"
    Command.RefreshAttendance -> "refresh_attendance"
    Command.RefreshAll -> "refresh_all"
    is Command.ToggleReadingDone -> "toggle_reading_done"
    is Command.SetAssessmentDone -> "set_assessment_done"
    is Command.Mark -> "mark"
    is Command.ArmMonitoring -> "arm_monitoring"
    Command.DisarmMonitoring -> "disarm_monitoring"
    Command.SystemLimitReached -> "system_limit_reached"
    Command.MonitorTick -> "monitor_tick"
}

private val ASSESSMENT_ORDER = compareBy<AssessmentItem> { it.dueDate ?: LocalDate.MAX }
    .thenBy { it.title }

private fun resultName(result: CommandResult): String = when (result) {
    is CommandResult.Completed -> "completed"
    is CommandResult.Marked -> "marked"
    is CommandResult.AlreadyMarked -> "already_marked"
    is CommandResult.Rejected -> "rejected"
}

private fun CommandResult.errorName(): String =
    if (this is CommandResult.Rejected) errorName(error) else "none"

private fun errorName(error: EngineError): String = when (error) {
    EngineError.CredentialsMissing -> "credentials_missing"
    is EngineError.InvalidCommand -> "invalid_command"
    is EngineError.AuthenticationFailed -> "authentication_failed"
    is EngineError.NetworkFailure -> "network_failure"
    is EngineError.LmsFailure -> "lms_failure"
    is EngineError.MarkWindowClosed -> "mark_window_closed"
    is EngineError.AttendanceLocationDenied -> "attendance_location_denied"
    is EngineError.MarkRejected -> "mark_rejected"
    EngineError.SystemLimitReached -> "system_limit_reached"
}
