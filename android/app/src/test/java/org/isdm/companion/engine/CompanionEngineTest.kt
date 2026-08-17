package org.isdm.companion.engine

import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.isdm.companion.domain.AttendanceLocationGateDecision
import org.isdm.companion.domain.LocationGateReason
import org.isdm.companion.domain.parseLmsTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionEngineTest {
    private val start = Instant.parse("2026-08-08T05:00:00Z") // 10:30 IST
    private val clock = TestClock(start)
    private val gateway = FakeGateway()
    private val notifier = RecordingNotifier()
    private val doneStore = MemoryReadingDoneStore()

    init {
        gateway.events += CalendarEvent(
            nid = "event-1",
            title = "Maths - Section B - Session 2",
            url = "/join/webinar?nid=event-1",
            start = "2026-08-08 10:30:00",
            end = "2026-08-08 12:00:00",
        )
        gateway.events += CalendarEvent(
            nid = "1285348",
            title = "Attendance - Maths - Section B - Session 2",
            url = "/classroom/1285348/view",
            start = "2026-08-08 10:30:00",
            end = "2026-08-08 12:00:00",
            trainers = "Trainer Two",
        )
        gateway.details["1285348"] = ClassroomDetail(
            nid = "1285348",
            title = "Maths",
            room = "Majlis",
            trainer = "Trainer Two",
            course = "Maths",
            marked = false,
            status = "Not Marked",
        )
        gateway.markabilityMap["1285348"] = Markability(markable = true, uid = "1042")
    }

    @Test
    fun `refresh assembles personalised session and room floor`() = runBlocking {
        val engine = engine()
        engine.dispatch(Command.ConfigureCredentials("student@example.com", "secret"))

        val result = engine.dispatch(Command.RefreshToday)
        assertTrue(result is CommandResult.Completed)
        val session = engine.state.value.sessions.single()
        assertEquals("1285348", session.nid)
        assertEquals("Maths", session.name)
        assertEquals("Majlis", session.room)
        assertEquals(6.0, session.floor)
        assertEquals("Floor 6", session.floorLabel)
        assertEquals(SessionState.OPEN, session.state)
        assertEquals("1042", engine.state.value.identity?.uid)
    }

    @Test
    fun `attendance refresh stores the LMS summary without recalculating it`() = runBlocking {
        gateway.attendance = AttendanceSummary(
            total = 54,
            present = 40,
            absent = 14,
            notMarked = 0,
            presentPercentage = java.math.BigDecimal("74.07"),
        )
        val engine = engine()
        engine.dispatch(Command.ConfigureCredentials("student@example.com", "secret"))

        val result = engine.dispatch(Command.RefreshAttendance)

        assertTrue(result is CommandResult.Completed)
        assertEquals(gateway.attendance, engine.state.value.attendanceSummary)
        assertEquals(1, gateway.attendanceCalls)
        assertEquals(start, engine.state.value.attendanceSync.lastSuccess)
    }

    @Test
    fun `command diagnostics retain timing and attendance state without session identifiers`() = runBlocking {
        val diagnostics = RecordingDiagnosticsLogger()
        val engine = engine(diagnostics = diagnostics)
        configureAndRefresh(engine)

        val fields = diagnostics.events.last { it.first == "command_finished" }.second

        assertEquals("refresh_today", fields["command"])
        assertEquals("completed", fields["result"])
        assertEquals("1", fields["sessions"])
        assertEquals("1", fields["markable_sessions"])
        assertEquals("false", fields["monitor_active"])
        assertEquals("0", fields["monitor_targets"])
        assertTrue(fields.getValue("duration_ms").toLong() >= 0L)
        assertFalse(fields.values.any { it.contains("1285348") })
    }

    @Test
    fun `monitor defaults to notify only and emits one opening notification`() = runBlocking {
        val engine = engine()
        configureAndRefresh(engine)

        val armed = engine.dispatch(Command.ArmMonitoring())
        assertTrue(armed is CommandResult.Completed)
        assertEquals(MonitoringMode.NOTIFY_ONLY, engine.state.value.monitor.mode)

        engine.dispatch(Command.MonitorTick)
        engine.dispatch(Command.MonitorTick)

        assertEquals(0, gateway.markPresentCalls)
        assertEquals(1, notifier.events.filterIsInstance<NotificationEvent.ClassOpen>().size)
        assertEquals(1, notifier.events.filterIsInstance<NotificationEvent.MonitoringArmed>().size)
    }

    @Test
    fun `monitoring cannot arm after its configured stop time`() = runBlocking {
        clock.current = Instant.parse("2026-08-08T15:30:00Z") // 21:00 IST
        val engine = engine()
        configureAndRefresh(engine)

        val result = engine.dispatch(Command.ArmMonitoring(MonitoringMode.AUTO_MARK))

        assertTrue(result is CommandResult.Rejected)
        val error = (result as CommandResult.Rejected).error
        assertTrue(error is EngineError.InvalidCommand)
        assertEquals("Monitoring stop time must be later today.", (error as EngineError.InvalidCommand).message)
        assertFalse(engine.state.value.monitor.active)
    }

    @Test
    fun `manual mark revalidates markability and is idempotent after confirmation`() = runBlocking {
        val telemetry = RecordingAttendanceTelemetry()
        val engine = engine(attendanceTelemetry = telemetry)
        configureAndRefresh(engine)
        gateway.markabilityCalls = 0

        val first = engine.dispatch(Command.Mark("1285348"))
        assertTrue(first is CommandResult.Marked)
        assertTrue(engine.state.value.sessions.single().marked)
        assertEquals(1, gateway.markPresentCalls)
        assertTrue(gateway.markabilityCalls >= 1)

        val second = engine.dispatch(Command.Mark("1285348"))
        assertTrue(second is CommandResult.AlreadyMarked)
        assertEquals(1, gateway.markPresentCalls)
        assertEquals("already_marked", telemetry.events.last().result)
    }

    @Test
    fun `attendance outcome is recorded for successful and blocked decisions`() = runBlocking {
        val telemetry = RecordingAttendanceTelemetry()
        val blocked = engine(locationGate = deniedLocationGate(), attendanceTelemetry = telemetry)
        configureAndRefresh(blocked)
        blocked.dispatch(Command.Mark("1285348"))

        assertEquals("blocked", telemetry.events.single().outcome)
        assertEquals(LocationGateReason.OUTSIDE_CAMPUS_ZONE, telemetry.events.single().gateReason)

        telemetry.events.clear()
        val successful = engine(attendanceTelemetry = telemetry)
        configureAndRefresh(successful)
        successful.dispatch(Command.Mark("1285348"))

        assertEquals("present", telemetry.events.single().outcome)
        assertEquals("manual", telemetry.events.single().method)
        assertEquals("marked_present", telemetry.events.single().result)
    }

    @Test
    fun `reading refresh retries one transient course failure before replacing the cache`() = runBlocking {
        val course = LmsCourse("12", "State, Market and Society")
        gateway.courseRows += course
        gateway.readingRows[course.catId] = mutableListOf(
            ReadingItem("501", "91", "7", "12", "Seeing Like a State", course.name, "Mandatory Reading", "https://lms/501"),
        )
        gateway.readingFailuresRemaining = 1
        val engine = engine()
        engine.dispatch(Command.ConfigureCredentials("student@example.com", "secret"))

        val result = engine.dispatch(Command.RefreshReadings)

        assertTrue(result is CommandResult.Completed)
        assertEquals(2, gateway.readingCalls)
        assertEquals(listOf("501"), engine.state.value.readings.map { it.vid })
    }

    @Test
    fun `manual mark denied by the Attendance Location Gate makes no LMS marking calls`() = runBlocking {
        val engine = engine(locationGate = deniedLocationGate())
        configureAndRefresh(engine)
        gateway.markabilityCalls = 0

        val result = engine.dispatch(Command.Mark("1285348"))

        assertTrue(result is CommandResult.Rejected)
        assertEquals(
            EngineError.AttendanceLocationDenied(LocationGateReason.OUTSIDE_CAMPUS_ZONE),
            (result as CommandResult.Rejected).error,
        )
        assertEquals(0, gateway.markabilityCalls)
        assertEquals(0, gateway.markPresentCalls)
    }

    @Test
    fun `automatic mark denied by the Attendance Location Gate makes no LMS marking calls and notifies`() = runBlocking {
        val engine = engine(locationGate = deniedLocationGate())
        configureAndRefresh(engine)
        gateway.markabilityCalls = 0
        engine.dispatch(Command.ArmMonitoring(MonitoringMode.AUTO_MARK))

        engine.dispatch(Command.MonitorTick)
        engine.dispatch(Command.MonitorTick)

        assertEquals(0, gateway.markabilityCalls)
        assertEquals(0, gateway.markPresentCalls)
        val failed = notifier.events.filterIsInstance<NotificationEvent.MarkFailed>().single()
        assertEquals(
            LocationGateReason.OUTSIDE_CAMPUS_ZONE,
            (failed.error as EngineError.AttendanceLocationDenied).reason,
        )
    }

    @Test
    fun `back to back monitoring refreshes reuse rate sensitive reads`() = runBlocking {
        val engine = engine()
        configureAndRefresh(engine)
        val calendarCalls = gateway.calendarCalls
        val classroomCalls = gateway.classroomCalls

        engine.dispatch(Command.ArmMonitoring())
        engine.dispatch(Command.MonitorTick)

        assertEquals(calendarCalls, gateway.calendarCalls)
        assertEquals(classroomCalls, gateway.classroomCalls)
    }

    @Test
    fun `mark window is checked again at action time`() = runBlocking {
        val engine = engine()
        configureAndRefresh(engine)
        gateway.markabilityMap["1285348"] = Markability(markable = false)

        val result = engine.dispatch(Command.Mark("1285348"))
        assertTrue(result is CommandResult.Rejected)
        assertTrue((result as CommandResult.Rejected).error is EngineError.MarkWindowClosed)
        assertEquals(0, gateway.markPresentCalls)
    }

    @Test
    fun `auto mark makes no more than three attempts for one session`() = runBlocking {
        val engine = engine()
        configureAndRefresh(engine)
        gateway.markFailuresRemaining = 10
        engine.dispatch(Command.ArmMonitoring(MonitoringMode.AUTO_MARK))

        repeat(5) { engine.dispatch(Command.MonitorTick) }

        assertEquals(3, gateway.markPresentCalls)
        assertEquals(3, engine.state.value.monitor.autoAttempts["1285348"])
    }

    @Test
    fun `automatic monitoring detects a window that opens after arming`() = runBlocking {
        clock.current = Instant.parse("2026-08-08T04:50:00Z") // 10:20 IST
        gateway.markabilityMap["1285348"] = Markability(markable = false)
        val engine = engine()
        configureAndRefresh(engine)
        engine.dispatch(Command.ArmMonitoring(MonitoringMode.AUTO_MARK))

        clock.current = Instant.parse("2026-08-08T05:01:00Z") // 10:31 IST
        repeat(5) { engine.dispatch(Command.MonitorTick) }
        assertEquals(0, gateway.markPresentCalls)
        assertEquals(emptyMap<String, Int>(), engine.state.value.monitor.autoAttempts)
        assertTrue(notifier.events.none { it is NotificationEvent.MarkFailed })

        gateway.markabilityMap["1285348"] = Markability(markable = true, uid = "1042")
        engine.dispatch(Command.MonitorTick)

        assertEquals(1, gateway.markPresentCalls)
        assertTrue(engine.state.value.sessions.single().marked)
        assertFalse(engine.state.value.monitor.active)
    }

    @Test
    fun `automatic monitoring keeps a live attendance session open for manual fallback`() = runBlocking {
        clock.current = Instant.parse("2026-08-08T04:50:00Z") // 10:20 IST
        gateway.markabilityMap["1285348"] = Markability(markable = false)
        val engine = engine()
        configureAndRefresh(engine)
        engine.dispatch(Command.ArmMonitoring(MonitoringMode.AUTO_MARK, targetSessionId = "1285348"))

        clock.current = Instant.parse("2026-08-08T05:01:00Z") // 10:31 IST
        engine.dispatch(Command.MonitorTick)

        assertEquals(SessionState.OPEN, engine.state.value.sessions.single().state)
        assertTrue(engine.state.value.sessions.single().markable)
    }

    @Test
    fun `successful automatic mark stops its monitoring window`() = runBlocking {
        val engine = engine()
        configureAndRefresh(engine)
        engine.dispatch(Command.ArmMonitoring(MonitoringMode.AUTO_MARK))

        engine.dispatch(Command.MonitorTick)

        assertEquals(1, gateway.markPresentCalls)
        assertFalse(engine.state.value.monitor.active)
    }

    @Test
    fun `overlapping automatic windows retain and mark both target sessions`() = runBlocking {
        gateway.events += CalendarEvent(
            nid = "2222222",
            title = "Attendance - Alpha - Section B - Session 2",
            url = "/classroom/2222222/view",
            start = "2026-08-08 10:30:00",
            end = "2026-08-08 12:00:00",
        )
        gateway.details["2222222"] = ClassroomDetail(
            nid = "2222222",
            title = "Alpha",
            marked = false,
            status = "Not Marked",
        )
        gateway.markabilityMap["2222222"] = Markability(markable = true, uid = "1042")
        val engine = engine()
        configureAndRefresh(engine)

        engine.dispatch(Command.ArmMonitoring(MonitoringMode.AUTO_MARK, targetSessionId = "1285348"))
        engine.dispatch(Command.ArmMonitoring(MonitoringMode.AUTO_MARK, targetSessionId = "2222222"))
        engine.dispatch(Command.MonitorTick)

        assertTrue(engine.state.value.sessions.all { it.marked })
        assertFalse(engine.state.value.monitor.active)
        assertEquals(2, gateway.markPresentCalls)
    }

    @Test
    fun `arming ends automatically on the next IST calendar day`() = runBlocking {
        val engine = engine()
        configureAndRefresh(engine)
        engine.dispatch(Command.ArmMonitoring())
        notifier.events.clear()

        clock.current = Instant.parse("2026-08-08T18:31:00Z") // 00:01 IST on 9 Aug
        engine.dispatch(Command.MonitorTick)

        assertFalse(engine.state.value.monitor.active)
        assertEquals(MonitoringStopReason.NEW_DAY, engine.state.value.monitor.reason)
        assertEquals(1, notifier.events.filterIsInstance<NotificationEvent.MonitoringStopped>().size)
        assertEquals(0, gateway.markPresentCalls)
    }

    @Test
    fun `Android foreground time limit stops monitoring visibly`() = runBlocking {
        val engine = engine()
        configureAndRefresh(engine)
        engine.dispatch(Command.ArmMonitoring())

        engine.dispatch(Command.SystemLimitReached)

        assertFalse(engine.state.value.monitor.active)
        assertEquals(MonitoringStopReason.SYSTEM_LIMIT, engine.state.value.monitor.reason)
        assertEquals(EngineError.SystemLimitReached, engine.state.value.error)
    }

    @Test
    fun `credentials are required before refresh or arming`() = runBlocking {
        val engine = engine()
        val refresh = engine.dispatch(Command.RefreshToday)
        assertTrue(refresh is CommandResult.Rejected)
        assertTrue((refresh as CommandResult.Rejected).error is EngineError.CredentialsMissing)

        val arm = engine.dispatch(Command.ArmMonitoring())
        assertTrue(arm is CommandResult.Rejected)
        assertTrue((arm as CommandResult.Rejected).error is EngineError.CredentialsMissing)
    }

    @Test
    fun `reconfiguring the same saved login preserves an active attendance window`() = runBlocking {
        val engine = engine()
        configureAndRefresh(engine)
        engine.dispatch(Command.ArmMonitoring(MonitoringMode.AUTO_MARK, targetSessionId = "1285348"))

        engine.dispatch(Command.ConfigureCredentials("student@example.com", "secret"))

        assertTrue(engine.state.value.monitor.active)
        assertEquals(setOf("1285348"), engine.state.value.monitor.targetSessionIds)
        assertEquals(1, engine.state.value.sessions.size)
    }

    @Test
    fun `sign out clears the live LMS session and attendance monitor`() = runBlocking {
        val engine = engine()
        configureAndRefresh(engine)
        engine.dispatch(Command.ArmMonitoring(MonitoringMode.AUTO_MARK, targetSessionId = "1285348"))

        engine.dispatch(Command.SignOut)

        assertFalse(engine.state.value.credentialsConfigured)
        assertEquals(null, engine.state.value.identity)
        assertTrue(engine.state.value.sessions.isEmpty())
        assertTrue(engine.state.value.scheduleSessions.isEmpty())
        assertFalse(engine.state.value.monitor.active)
        assertEquals(2, gateway.resetSessionCalls)
    }

    @Test
    fun `schedule refresh retains today separately and loads a rolling range`() = runBlocking {
        gateway.events += CalendarEvent(
            nid = "event-2",
            title = "Policy - Section B - Session 3",
            url = "/join/webinar?nid=event-2",
            start = "2026-08-11 09:00:00",
            end = "2026-08-11 10:15:00",
        )
        val engine = engine()
        engine.dispatch(Command.ConfigureCredentials("student@example.com", "secret"))
        engine.dispatch(Command.RefreshAll)

        assertEquals(1, engine.state.value.sessions.size)
        assertEquals(listOf("Maths", "Policy"), engine.state.value.scheduleSessions.map { it.name })
        assertEquals(LocalDate.parse("2026-08-08"), engine.state.value.scheduleStart)
        assertEquals(LocalDate.parse("2026-08-22"), engine.state.value.scheduleEndExclusive)
    }

    @Test
    fun `schedule refresh preserves today's live attendance state`() = runBlocking {
        val engine = engine()
        configureAndRefresh(engine)

        engine.dispatch(Command.RefreshSchedule())

        val scheduled = engine.state.value.scheduleSessions.single()
        assertEquals(SessionState.OPEN, scheduled.state)
        assertTrue(scheduled.markable)
    }

    @Test
    fun `confirmed attendance updates the rolling schedule state`() = runBlocking {
        val engine = engine()
        configureAndRefresh(engine)
        engine.dispatch(Command.RefreshSchedule())

        engine.dispatch(Command.Mark("1285348"))

        val scheduled = engine.state.value.scheduleSessions.single()
        assertTrue(scheduled.marked)
        assertEquals(SessionState.MARKED, scheduled.state)
    }

    @Test
    fun `reading refresh identifies mandatory items and local done survives refresh`() = runBlocking {
        val course = LmsCourse("12", "State, Market and Society")
        gateway.courseRows += course
        gateway.readingRows[course.catId] = mutableListOf(
            ReadingItem("501", "91", "7", "12", "Seeing Like a State", course.name, "Mandatory Reading", "https://lms/501", mandatory = true),
            ReadingItem("502", "92", "7", "12", "Markets", course.name, "Course Readings", "https://lms/502"),
        )
        val engine = engine()
        engine.dispatch(Command.ConfigureCredentials("student@example.com", "secret"))
        engine.dispatch(Command.RefreshReadings)
        engine.dispatch(Command.ToggleReadingDone("501"))
        engine.dispatch(Command.RefreshReadings)

        assertEquals(listOf("502", "501"), engine.state.value.readings.map { it.vid })
        assertTrue(engine.state.value.readings.last().done)
        assertEquals(setOf("501"), doneStore.load())
    }

    private fun engine(
        locationGate: AttendanceLocationGatePort = AllowAttendanceLocationGate,
        diagnostics: DiagnosticsLogger = NoopDiagnosticsLogger,
        attendanceTelemetry: AttendanceTelemetryPort = NoopAttendanceTelemetry,
    ) = CompanionEngine(
        gateway,
        clock,
        notifier,
        readingDoneStore = doneStore,
        attendanceLocationGate = locationGate,
        diagnostics = diagnostics,
        attendanceTelemetry = attendanceTelemetry,
    )

    private fun deniedLocationGate() = AttendanceLocationGatePort {
        AttendanceLocationGateDecision(
            allowsMark = false,
            reason = LocationGateReason.OUTSIDE_CAMPUS_ZONE,
        )
    }

    private suspend fun configureAndRefresh(engine: CompanionEngine) {
        engine.dispatch(Command.ConfigureCredentials("student@example.com", "secret"))
        engine.dispatch(Command.RefreshToday)
    }
}

private class RecordingAttendanceTelemetry : AttendanceTelemetryPort {
    val events = mutableListOf<AttendanceTelemetryEvent>()
    override suspend fun record(event: AttendanceTelemetryEvent) {
        events += event
    }
}

private class TestClock(var current: Instant) : Clock {
    override fun now(): Instant = current
}

private class RecordingNotifier : Notifier {
    val events = mutableListOf<NotificationEvent>()

    override suspend fun notify(event: NotificationEvent) {
        events += event
    }
}

private class RecordingDiagnosticsLogger : DiagnosticsLogger {
    val events = mutableListOf<Pair<String, Map<String, String>>>()

    override fun log(event: String, attributes: Map<String, String>, error: Throwable?) {
        events += event to attributes
    }
}

private class FakeGateway : LmsGateway {
    val events = mutableListOf<CalendarEvent>()
    val details = mutableMapOf<String, ClassroomDetail>()
    val markabilityMap = mutableMapOf<String, Markability>()
    val courseRows = mutableListOf<LmsCourse>()
    val readingRows = mutableMapOf<String, MutableList<ReadingItem>>()
    var markFailuresRemaining = 0
    var markPresentCalls = 0
    var markabilityCalls = 0
    var calendarCalls = 0
    var classroomCalls = 0
    var resetSessionCalls = 0
    var readingCalls = 0
    var readingFailuresRemaining = 0
    var attendance = AttendanceSummary(0, 0, 0, 0, java.math.BigDecimal.ZERO)
    var attendanceCalls = 0

    override suspend fun login(credentials: Credentials): Identity = Identity("1042", "Student")

    override suspend fun courses(): List<LmsCourse> = courseRows.toList()

    override suspend fun readings(course: LmsCourse): List<ReadingItem> {
        readingCalls++
        if (readingFailuresRemaining > 0) {
            readingFailuresRemaining--
            throw IOException("temporary reading network failure")
        }
        return readingRows[course.catId].orEmpty()
    }

    override suspend fun calendar(start: LocalDate, endExclusive: LocalDate): List<CalendarEvent> {
        calendarCalls++
        return events.filter { event ->
            val instant = parseLmsTime(event.start) ?: return@filter false
            val date = instant.atZone(LMS_ZONE).toLocalDate()
            date >= start && date < endExclusive
        }
    }

    override suspend fun classroom(nid: String): ClassroomDetail {
        classroomCalls++
        return details[nid] ?: error("missing detail for $nid")
    }

    override suspend fun markability(): Map<String, Markability> {
        markabilityCalls++
        return markabilityMap.toMap()
    }

    override suspend fun attendanceSummary(): AttendanceSummary {
        attendanceCalls++
        return attendance
    }

    override suspend fun markPresent(nid: String): ClassroomDetail {
        markPresentCalls++
        if (markFailuresRemaining > 0) {
            markFailuresRemaining--
            throw IOException("temporary network failure")
        }
        val old = details[nid] ?: error("missing detail for $nid")
        val updated = old.copy(marked = true, status = "Present")
        details[nid] = updated
        return updated
    }

    override fun resetSession() {
        resetSessionCalls++
    }
}

private class MemoryReadingDoneStore : ReadingDoneStore {
    private val values = mutableSetOf<String>()
    override fun load(): Set<String> = values.toSet()
    override fun setDone(readingId: String, done: Boolean) {
        if (done) values += readingId else values -= readingId
    }
}
