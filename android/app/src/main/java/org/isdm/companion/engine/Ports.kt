package org.isdm.companion.engine

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

import org.isdm.companion.domain.CalendarEvent as DomainCalendarEvent
import org.isdm.companion.domain.Cohorts as DomainCohorts
import org.isdm.companion.domain.LMS_ZONE as DOMAIN_LMS_ZONE
import org.isdm.companion.domain.SessionState as DomainSessionState

/** Type aliases keep the data seam and the pure domain model on the same wire types. */
typealias CalendarEvent = DomainCalendarEvent
typealias Cohorts = DomainCohorts
typealias SessionState = DomainSessionState
typealias Session = CompanionSession

/** The LMS timestamps are wall-clock IST; this is deliberately not the device default. */
val LMS_ZONE = DOMAIN_LMS_ZONE

data class Credentials(val email: String, val password: String)

fun interface Clock {
    fun now(): Instant
}

object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

/**
 * The only network seam the engine knows about. Implementations own HTTP, cookies, HTML
 * parsing, and the LMS's login/session details.
 */
interface LmsGateway {
    suspend fun login(credentials: Credentials): Identity
    suspend fun calendar(start: LocalDate, endExclusive: LocalDate): List<CalendarEvent>
    suspend fun classroom(nid: String): ClassroomDetail
    suspend fun markability(): Map<String, Markability>
    suspend fun markPresent(nid: String): ClassroomDetail

    /** Called when the engine decides that a kept-alive session is no longer trustworthy. */
    fun resetSession() = Unit
}

/** A side-effect seam kept outside the engine's state model so tests can capture every alert. */
fun interface Notifier {
    suspend fun notify(event: NotificationEvent)
}

object NoopNotifier : Notifier {
    override suspend fun notify(event: NotificationEvent) = Unit
}

data class Identity(val uid: String, val displayName: String? = null)

data class ClassroomDetail(
    val nid: String,
    val title: String? = null,
    val room: String? = null,
    val trainer: String? = null,
    val course: String? = null,
    val marked: Boolean = false,
    val status: String? = null,
    val comment: String? = null,
)

data class Markability(
    val markable: Boolean,
    val uid: String? = null,
)

enum class MonitoringMode {
    NOTIFY_ONLY,
    AUTO_MARK,
}

enum class MonitoringStopReason {
    DISARMED,
    NEW_DAY,
    STOP_TIME,
    SYSTEM_LIMIT,
}

/** A domain session plus the live state and deadline the Android UI needs. */
data class CompanionSession(
    val nid: String?,
    val eventNid: String?,
    val name: String,
    val cohort: String?,
    val sessionNumber: Int?,
    val start: Instant,
    /** Always present for UI rendering; a missing LMS end is normalised to start by the engine. */
    val end: Instant,
    val subject: String? = null,
    val trainer: String? = null,
    val room: String? = null,
    val floor: Double? = null,
    val floorLabel: String? = null,
    val marked: Boolean = false,
    val markable: Boolean = false,
    val state: SessionState = SessionState.UPCOMING,
    val lateAfter: Instant? = null,
)

data class MonitoringStatus(
    val active: Boolean = false,
    val mode: MonitoringMode? = null,
    val armedDate: LocalDate? = null,
    val armedAt: Instant? = null,
    val stopAt: Instant? = null,
    val reason: MonitoringStopReason? = null,
    val autoAttempts: Map<String, Int> = emptyMap(),
)

data class SyncStatus(
    val inProgress: Boolean = false,
    val lastSuccess: Instant? = null,
    val error: EngineError? = null,
)

data class CompanionState(
    val now: Instant,
    val today: LocalDate,
    val credentialsConfigured: Boolean = false,
    val identity: Identity? = null,
    val sessions: List<CompanionSession> = emptyList(),
    val monitor: MonitoringStatus = MonitoringStatus(),
    val sync: SyncStatus = SyncStatus(),
    val error: EngineError? = null,
)

sealed interface EngineError {
    data object CredentialsMissing : EngineError
    data class InvalidCommand(val message: String) : EngineError
    data class AuthenticationFailed(val message: String) : EngineError
    data class NetworkFailure(val message: String) : EngineError
    data class LmsFailure(val message: String) : EngineError
    data class MarkWindowClosed(val sessionId: String) : EngineError
    data class MarkRejected(val sessionId: String, val message: String) : EngineError
    data object SystemLimitReached : EngineError
}

sealed interface NotificationEvent {
    data class MonitoringArmed(
        val mode: MonitoringMode,
        val armedDate: LocalDate,
        val stopAt: Instant?,
    ) : NotificationEvent

    data class MonitoringStopped(val reason: MonitoringStopReason) : NotificationEvent

    data class ClassOpen(val session: CompanionSession) : NotificationEvent

    data class MarkedPresent(
        val session: CompanionSession,
        val automatic: Boolean,
    ) : NotificationEvent

    data class MarkFailed(
        val session: CompanionSession,
        val error: EngineError,
    ) : NotificationEvent
}

sealed interface Command {
    data class ConfigureCredentials(val email: String, val password: String) : Command
    data object RefreshToday : Command
    data class Mark(val sessionId: String) : Command
    data class ArmMonitoring(
        val mode: MonitoringMode = MonitoringMode.NOTIFY_ONLY,
        /** Local IST stop time. Null means no configured clock stop; day rollover still applies. */
        val stopAt: LocalTime? = LocalTime.of(18, 0),
    ) : Command
    data object DisarmMonitoring : Command
    /** Android stopped the foreground monitor after its platform time quota. */
    data object SystemLimitReached : Command
    data object MonitorTick : Command
}

sealed interface CommandResult {
    data class Completed(val state: CompanionState) : CommandResult
    data class Marked(
        val sessionId: String,
        val automatic: Boolean,
        val state: CompanionState,
    ) : CommandResult

    data class AlreadyMarked(val sessionId: String, val state: CompanionState) : CommandResult
    data class Rejected(val error: EngineError, val state: CompanionState) : CommandResult
}
