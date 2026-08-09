package org.isdm.companion.engine

import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
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
    fun `manual mark revalidates markability and is idempotent after confirmation`() = runBlocking {
        val engine = engine()
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

    private fun engine() = CompanionEngine(gateway, clock, notifier)

    private suspend fun configureAndRefresh(engine: CompanionEngine) {
        engine.dispatch(Command.ConfigureCredentials("student@example.com", "secret"))
        engine.dispatch(Command.RefreshToday)
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

private class FakeGateway : LmsGateway {
    val events = mutableListOf<CalendarEvent>()
    val details = mutableMapOf<String, ClassroomDetail>()
    val markabilityMap = mutableMapOf<String, Markability>()
    var markFailuresRemaining = 0
    var markPresentCalls = 0
    var markabilityCalls = 0
    var calendarCalls = 0
    var classroomCalls = 0

    override suspend fun login(credentials: Credentials): Identity = Identity("1042", "Student")

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
}
