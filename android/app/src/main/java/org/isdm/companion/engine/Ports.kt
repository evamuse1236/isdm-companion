package org.isdm.companion.engine

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.time.LocalTime

import org.isdm.companion.domain.AttendanceLocationGateDecision
import org.isdm.companion.domain.CalendarEvent as DomainCalendarEvent
import org.isdm.companion.domain.Cohorts as DomainCohorts
import org.isdm.companion.domain.LMS_ZONE as DOMAIN_LMS_ZONE
import org.isdm.companion.domain.LocationGateReason
import org.isdm.companion.domain.SessionState as DomainSessionState

/** Type aliases keep the data seam and the pure domain model on the same wire types. */
typealias CalendarEvent = DomainCalendarEvent
typealias Cohorts = DomainCohorts
typealias SessionState = DomainSessionState
typealias Session = CompanionSession

/** The LMS timestamps are wall-clock IST; this is deliberately not the device default. */
val LMS_ZONE = DOMAIN_LMS_ZONE

val AUTO_ATTENDANCE_LEAD: Duration = Duration.ofMinutes(10)
val AUTO_ATTENDANCE_GRACE: Duration = Duration.ofMinutes(15)

data class Credentials(val email: String, val password: String)

fun interface Clock {
    fun now(): Instant
}

object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

/** The attendance-action boundary receives only a privacy-safe gate decision. */
fun interface AttendanceLocationGatePort {
    suspend fun evaluate(now: Instant): AttendanceLocationGateDecision
}

/** Test/default adapter. Android production wiring replaces this with a fail-closed adapter. */
object AllowAttendanceLocationGate : AttendanceLocationGatePort {
    override suspend fun evaluate(now: Instant): AttendanceLocationGateDecision =
        AttendanceLocationGateDecision(allowsMark = true)
}

interface DiagnosticsLogger {
    fun log(
        event: String,
        attributes: Map<String, String> = emptyMap(),
        error: Throwable? = null,
    )
}

object NoopDiagnosticsLogger : DiagnosticsLogger {
    override fun log(event: String, attributes: Map<String, String>, error: Throwable?) = Unit
}

data class AttendanceTelemetryEvent(
    val method: String,
    val sessionId: String,
    val sessionLabel: String,
    val outcome: String,
    val result: String,
    val gateAllowed: Boolean?,
    val gateReason: LocationGateReason?,
    val lmsMarkable: Boolean?,
)

fun interface AttendanceTelemetryPort {
    suspend fun record(event: AttendanceTelemetryEvent)
}

object NoopAttendanceTelemetry : AttendanceTelemetryPort {
    override suspend fun record(event: AttendanceTelemetryEvent) = Unit
}

/**
 * The only network seam the engine knows about. Implementations own HTTP, cookies, HTML
 * parsing, and the LMS's login/session details.
 */
interface LmsGateway {
    suspend fun login(credentials: Credentials): Identity
    suspend fun calendar(start: LocalDate, endExclusive: LocalDate): List<CalendarEvent>
    suspend fun courses(): List<LmsCourse>
    suspend fun readings(course: LmsCourse): List<ReadingItem>
    suspend fun assessments(): List<AssessmentItem> = emptyList()
    suspend fun classroom(nid: String): ClassroomDetail
    suspend fun attendanceSummary(): AttendanceSummary
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

interface ReadingDoneStore {
    fun load(): Set<String>
    fun setDone(readingId: String, done: Boolean)
}

object NoopReadingDoneStore : ReadingDoneStore {
    override fun load(): Set<String> = emptySet()
    override fun setDone(readingId: String, done: Boolean) = Unit
}

interface AssessmentDoneStore {
    fun loadAssessmentDone(): Set<String>
    fun setAssessmentDone(assessmentId: String, done: Boolean)
}

object NoopAssessmentDoneStore : AssessmentDoneStore {
    override fun loadAssessmentDone(): Set<String> = emptySet()
    override fun setAssessmentDone(assessmentId: String, done: Boolean) = Unit
}

data class CachedSchedule(
    val start: LocalDate,
    val endExclusive: LocalDate,
    val sessions: List<CompanionSession>,
    val syncedAt: Instant,
)

interface CompanionCacheStore {
    /** Selects an account namespace before any cached personal data is loaded or saved. */
    fun selectAccount(accountId: String)
    fun loadSchedule(): CachedSchedule?
    fun saveSchedule(schedule: CachedSchedule)
    fun loadReadings(): List<ReadingItem>
    fun saveReadings(readings: List<ReadingItem>)
    fun loadAssessments(): List<AssessmentItem> = emptyList()
    fun saveAssessments(assessments: List<AssessmentItem>) = Unit
    fun loadFacultyProfiles(): List<FacultyProfile>
    fun saveFacultyProfiles(profiles: List<FacultyProfile>)
}

object NoopCompanionCacheStore : CompanionCacheStore {
    override fun selectAccount(accountId: String) = Unit
    override fun loadSchedule(): CachedSchedule? = null
    override fun saveSchedule(schedule: CachedSchedule) = Unit
    override fun loadReadings(): List<ReadingItem> = emptyList()
    override fun saveReadings(readings: List<ReadingItem>) = Unit
    override fun loadAssessments(): List<AssessmentItem> = emptyList()
    override fun saveAssessments(assessments: List<AssessmentItem>) = Unit
    override fun loadFacultyProfiles(): List<FacultyProfile> = emptyList()
    override fun saveFacultyProfiles(profiles: List<FacultyProfile>) = Unit
}

data class Identity(val uid: String, val displayName: String? = null)

data class LmsCourse(
    val catId: String,
    val name: String,
)

data class LmsReadingSection(
    val sid: String,
    val catId: String,
    val name: String,
)

enum class LmsReadingProgress {
    UNOPENED,
    VIEWED,
    IN_PROGRESS,
    COMPLETED,
    UNKNOWN,
}

data class ReadingItem(
    val vid: String,
    val sid: String,
    val cid: String,
    val catId: String,
    val title: String,
    val courseName: String,
    val sectionName: String,
    val sourceUrl: String,
    val sessionNumber: Int? = null,
    val progress: LmsReadingProgress = LmsReadingProgress.UNKNOWN,
    val mandatory: Boolean = false,
    val done: Boolean = false,
    val courseOutlineTitle: String? = null,
    val courseOutlineUrl: String? = null,
)

data class AssessmentItem(
    val id: String,
    val title: String,
    val status: String,
    val dueDate: LocalDate?,
    val endDate: LocalDate?,
    val submissionUrl: String,
    val resourceTitle: String? = null,
    val resourceUrl: String? = null,
    val done: Boolean = false,
)

internal fun AssessmentItem.isLmsSubmitted(): Boolean {
    val normalized = status.lowercase(Locale.ENGLISH)
    return "submitted" in normalized && "not submitted" !in normalized
}

data class ClassroomDetail(
    val nid: String,
    val title: String? = null,
    val room: String? = null,
    val trainer: String? = null,
    val course: String? = null,
    val marked: Boolean = false,
    val status: String? = null,
    val comment: String? = null,
    val end: Instant? = null,
)

/** Completed-session totals from the LMS historical attendance report. */
data class AttendanceSummary(
    val total: Int,
    val present: Int,
    val absent: Int,
    val notMarked: Int,
    val presentPercentage: BigDecimal,
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
    ATTENDANCE_MARKED,
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
    val endEstimated: Boolean = false,
)

data class MonitoringStatus(
    val active: Boolean = false,
    val mode: MonitoringMode? = null,
    val armedDate: LocalDate? = null,
    val armedAt: Instant? = null,
    val stopAt: Instant? = null,
    val reason: MonitoringStopReason? = null,
    val autoAttempts: Map<String, Int> = emptyMap(),
    val targetSessionIds: Set<String> = emptySet(),
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
    val detectedCohorts: Cohorts = Cohorts(),
    val sessions: List<CompanionSession> = emptyList(),
    val scheduleStart: LocalDate = today,
    val scheduleEndExclusive: LocalDate = today.plusDays(14),
    val scheduleSessions: List<CompanionSession> = emptyList(),
    val readings: List<ReadingItem> = emptyList(),
    val assessments: List<AssessmentItem> = emptyList(),
    val facultyProfiles: List<FacultyProfile> = emptyList(),
    val attendanceSummary: AttendanceSummary? = null,
    val monitor: MonitoringStatus = MonitoringStatus(),
    val sync: SyncStatus = SyncStatus(),
    val scheduleSync: SyncStatus = SyncStatus(),
    val readingSync: SyncStatus = SyncStatus(),
    val assessmentSync: SyncStatus = SyncStatus(),
    val attendanceSync: SyncStatus = SyncStatus(),
    val error: EngineError? = null,
)

sealed interface EngineError {
    data object CredentialsMissing : EngineError
    data class InvalidCommand(val message: String) : EngineError
    data class AuthenticationFailed(val message: String) : EngineError
    data class NetworkFailure(val message: String) : EngineError
    data class LmsFailure(val message: String) : EngineError
    data class MarkWindowClosed(val sessionId: String) : EngineError
    data class AttendanceLocationDenied(val reason: LocationGateReason) : EngineError
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
    data object SignOut : Command
    data object RefreshToday : Command
    data class RefreshSchedule(val days: Int = 14) : Command
    data object RefreshReadings : Command
    data object RefreshAttendance : Command
    data object RefreshAll : Command
    data class ToggleReadingDone(val readingId: String) : Command
    data class SetAssessmentDone(val assessmentId: String, val done: Boolean) : Command
    data class Mark(val sessionId: String) : Command
    data class ArmMonitoring(
        val mode: MonitoringMode = MonitoringMode.NOTIFY_ONLY,
        /** Local IST stop time. Null means no configured clock stop; day rollover still applies. */
        val stopAt: LocalTime? = LocalTime.of(18, 0),
        /** Attendance Session selected by an exact alarm; null retains unscoped manual monitoring. */
        val targetSessionId: String? = null,
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
