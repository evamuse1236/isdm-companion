package org.isdm.companion

import android.app.Application
import android.os.Build
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.isdm.companion.data.RealLmsAdapter
import org.isdm.companion.data.LmsDiagnosticReporter
import org.isdm.companion.domain.AttendanceLocationGate
import org.isdm.companion.domain.AttendanceLocationGateDecision
import org.isdm.companion.domain.LocationGateReason
import org.isdm.companion.engine.AttendanceLocationGatePort
import org.isdm.companion.engine.AttendanceTelemetryEvent
import org.isdm.companion.engine.AttendanceTelemetryPort
import org.isdm.companion.engine.CompanionEngine
import org.isdm.companion.engine.CachedSchedule
import org.isdm.companion.engine.DiagnosticsLogger
import org.isdm.companion.platform.AndroidNotifier
import org.isdm.companion.platform.AndroidDiagnosticsLogger
import org.isdm.companion.platform.AndroidLocationEvidenceProvider
import org.isdm.companion.platform.AndroidProcessExitReporter
import org.isdm.companion.platform.AutoAttendanceScheduler
import org.isdm.companion.platform.AutoAttendanceStore
import org.isdm.companion.platform.CompanionLocalStore
import org.isdm.companion.platform.CompanionSyncWorker
import org.isdm.companion.platform.BetaDiagnosticsLogger
import org.isdm.companion.platform.BetaManager
import org.isdm.companion.platform.SecureCredentialStore
import org.isdm.companion.platform.StoredCredentials

class CompanionApplication : Application() {
    lateinit var credentialStore: SecureCredentialStore
        private set

    lateinit var engine: CompanionEngine
        private set

    lateinit var lmsAdapter: RealLmsAdapter
        private set

    lateinit var diagnostics: DiagnosticsLogger
        private set

    lateinit var betaManager: BetaManager
        private set

    lateinit var autoAttendanceStore: AutoAttendanceStore
        private set

    lateinit var autoAttendanceScheduler: AutoAttendanceScheduler
        private set

    lateinit var localStore: CompanionLocalStore
        private set

    override fun onCreate() {
        super.onCreate()
        credentialStore = SecureCredentialStore(this)
        autoAttendanceStore = AutoAttendanceStore(this)
        betaManager = BetaManager.create(this, autoAttendanceStore::isEnabled)
        diagnostics = BetaDiagnosticsLogger(AndroidDiagnosticsLogger(this), betaManager)
        AndroidProcessExitReporter(this, diagnostics).reportPreviousExits()
        autoAttendanceScheduler = AutoAttendanceScheduler(this, autoAttendanceStore, diagnostics)
        localStore = CompanionLocalStore(this)
        val savedCredentials = credentialStore.load()
        val setupReady = betaManager.automaticAttendanceSetupReady &&
            autoAttendanceScheduler.hasRequiredSystemAccess()
        if (autoAttendanceStore.isEnabled() && !setupReady) {
            autoAttendanceStore.setEnabled(false)
            autoAttendanceScheduler.cancel()
        }
        restoreAutoAttendanceOnProcessStart(
            enabled = autoAttendanceStore.isEnabled(),
            setupReady = setupReady,
            credentials = savedCredentials,
            selectAccount = localStore::selectAccount,
            loadSchedule = localStore::loadSchedule,
            schedule = { cached -> autoAttendanceScheduler.schedule(cached.sessions, Instant.now()) },
        )
        lmsAdapter = RealLmsAdapter(
            lmsDiagnosticReporter = LmsDiagnosticReporter { endpointLabel, httpStatus, failureType ->
                betaManager.queueLmsDiagnostic(endpointLabel, httpStatus, failureType)
            },
        )
        val locationEvidenceProvider = AndroidLocationEvidenceProvider(this, diagnostics = diagnostics)
        val locationGate = AndroidAttendanceLocationGatePort(
            evidenceProvider = locationEvidenceProvider,
            diagnostics = diagnostics,
        )
        engine = CompanionEngine(
            gateway = lmsAdapter,
            notifier = AndroidNotifier(this),
            readingDoneStore = localStore,
            assessmentDoneStore = localStore,
            cacheStore = localStore,
            diagnostics = diagnostics,
            attendanceLocationGate = locationGate,
            attendanceTelemetry = AndroidBetaAttendanceTelemetry(
                betaManager = betaManager,
                evidenceProvider = locationEvidenceProvider,
            ),
            facultyDirectory = lmsAdapter,
        )
        AndroidNotifier.createChannels(this)
        CompanionSyncWorker.schedule(this)
        diagnostics.log(
            "application_started",
            mapOf(
                "android_sdk" to Build.VERSION.SDK_INT.toString(),
                "auto_attendance_enabled" to autoAttendanceStore.isEnabled().toString(),
                "build_type" to BuildConfig.BUILD_TYPE,
                "device_model" to Build.MODEL,
                "saved_login" to (savedCredentials != null).toString(),
                "version_code" to BuildConfig.VERSION_CODE.toString(),
                "version_name" to BuildConfig.VERSION_NAME,
            ),
        )
    }
}

private class AndroidBetaAttendanceTelemetry(
    private val betaManager: BetaManager,
    private val evidenceProvider: AndroidLocationEvidenceProvider,
) : AttendanceTelemetryPort {
    override suspend fun record(event: AttendanceTelemetryEvent) {
        betaManager.queueAttendance(
            method = event.method,
            sessionId = event.sessionId,
            sessionLabel = event.sessionLabel,
            outcome = event.outcome,
            result = event.result,
            gateAllowed = event.gateAllowed,
            gateReason = event.gateReason?.name,
            lmsMarkable = event.lmsMarkable,
            evidence = evidenceProvider.latestEvidence(),
        )
    }
}

internal fun restoreAutoAttendanceOnProcessStart(
    enabled: Boolean,
    setupReady: Boolean,
    credentials: StoredCredentials?,
    selectAccount: (String) -> Unit,
    loadSchedule: () -> CachedSchedule?,
    schedule: (CachedSchedule) -> Unit,
) {
    if (!enabled || !setupReady || credentials == null) return
    selectAccount(credentials.email)
    loadSchedule()?.let(schedule)
}

/** Android boundary that never exposes raw location to the engine or diagnostic logs. */
private class AndroidAttendanceLocationGatePort(
    private val evidenceProvider: AndroidLocationEvidenceProvider,
    private val diagnostics: DiagnosticsLogger,
    private val gate: AttendanceLocationGate = AttendanceLocationGate(),
) : AttendanceLocationGatePort {
    override suspend fun evaluate(now: Instant): AttendanceLocationGateDecision {
        val decision = try {
            val evidence = evidenceProvider.currentEvidence()
                ?: evidenceProvider.bestRecentLastKnownEvidence(now)
            gate.evaluate(evidence, now)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            AttendanceLocationGateDecision(
                allowsMark = false,
                reason = LocationGateReason.MISSING_EVIDENCE,
            )
        }
        runCatching {
            diagnostics.log(
                "attendance_location_gate_evaluated",
                mapOf(
                    "outcome" to if (decision.allowsMark) "allowed" else "denied",
                    "reason" to (decision.reason?.name ?: "none"),
                ),
            )
        }
        return decision
    }
}
