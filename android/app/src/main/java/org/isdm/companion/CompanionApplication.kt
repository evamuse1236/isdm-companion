package org.isdm.companion

import android.app.Application
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.isdm.companion.data.RealLmsAdapter
import org.isdm.companion.domain.AttendanceLocationGate
import org.isdm.companion.domain.AttendanceLocationGateDecision
import org.isdm.companion.domain.LocationGateReason
import org.isdm.companion.engine.AttendanceLocationGatePort
import org.isdm.companion.engine.CompanionEngine
import org.isdm.companion.platform.AndroidNotifier
import org.isdm.companion.platform.AndroidDiagnosticsLogger
import org.isdm.companion.platform.AndroidLocationEvidenceProvider
import org.isdm.companion.platform.AutoAttendanceScheduler
import org.isdm.companion.platform.AutoAttendanceStore
import org.isdm.companion.platform.CompanionLocalStore
import org.isdm.companion.platform.CompanionSyncWorker
import org.isdm.companion.platform.SecureCredentialStore

class CompanionApplication : Application() {
    lateinit var credentialStore: SecureCredentialStore
        private set

    lateinit var engine: CompanionEngine
        private set

    lateinit var lmsAdapter: RealLmsAdapter
        private set

    lateinit var diagnostics: AndroidDiagnosticsLogger
        private set

    lateinit var autoAttendanceStore: AutoAttendanceStore
        private set

    lateinit var autoAttendanceScheduler: AutoAttendanceScheduler
        private set

    override fun onCreate() {
        super.onCreate()
        diagnostics = AndroidDiagnosticsLogger(this)
        credentialStore = SecureCredentialStore(this)
        autoAttendanceStore = AutoAttendanceStore(this)
        autoAttendanceScheduler = AutoAttendanceScheduler(this, autoAttendanceStore)
        val localStore = CompanionLocalStore(this)
        lmsAdapter = RealLmsAdapter()
        val locationGate = AndroidAttendanceLocationGatePort(
            evidenceProvider = AndroidLocationEvidenceProvider(this),
            diagnostics = diagnostics,
        )
        engine = CompanionEngine(
            gateway = lmsAdapter,
            notifier = AndroidNotifier(this),
            readingDoneStore = localStore,
            cacheStore = localStore,
            diagnostics = diagnostics,
            attendanceLocationGate = locationGate,
            facultyDirectory = lmsAdapter,
        )
        AndroidNotifier.createChannels(this)
        CompanionSyncWorker.schedule(this)
        diagnostics.log("application_started")
    }
}

/** Android boundary that never exposes raw location to the engine or diagnostic logs. */
private class AndroidAttendanceLocationGatePort(
    private val evidenceProvider: AndroidLocationEvidenceProvider,
    private val diagnostics: AndroidDiagnosticsLogger,
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
