package org.isdm.companion.platform

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.isdm.companion.BuildConfig
import org.isdm.companion.CompanionApplication
import org.isdm.companion.domain.LocationEvidence
import org.isdm.companion.engine.DiagnosticsLogger

class BetaInstallationStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): BetaInstallation? {
        val testerCode = preferences.getString(KEY_TESTER_CODE, null) ?: return null
        val installationId = preferences.getString(KEY_INSTALLATION_ID, null) ?: return null
        val token = preferences.getString(KEY_INSTALL_TOKEN, null) ?: return null
        return BetaInstallation(testerCode, installationId, token)
    }

    fun save(installation: BetaInstallation): Boolean = preferences.edit()
        .putString(KEY_TESTER_CODE, installation.testerCode)
        .putString(KEY_INSTALLATION_ID, installation.installationId)
        .putString(KEY_INSTALL_TOKEN, installation.installToken)
        .commit()

    private companion object {
        const val FILE_NAME = "beta_installation"
        const val KEY_TESTER_CODE = "tester_code"
        const val KEY_INSTALLATION_ID = "installation_id"
        const val KEY_INSTALL_TOKEN = "install_token"
    }
}

class BetaManager(
    private val context: Context,
    private val installationStore: BetaInstallationStore,
    private val eventStore: JsonBetaEventStore,
    private val requestStore: JsonBetaRequestStore,
    private val api: BetaApiClient,
    private val autoAttendanceEnabled: () -> Boolean,
) {
    private val uiPreferences = context.getSharedPreferences("beta_ui", Context.MODE_PRIVATE)
    val installation: BetaInstallation?
        get() = installationStore.load()

    val isEnrolled: Boolean
        get() = installation != null

    fun enroll(inviteCode: String, section: String, plc: String?): BetaInstallation {
        val installation = api.enroll(
            BetaEnrollment(
                inviteCode = inviteCode,
                section = section,
                plc = plc,
                consentVersion = CONSENT_VERSION,
                device = deviceInfo(),
            ),
        )
        check(installationStore.save(installation)) { "Could not securely save beta enrollment." }
        log("beta_enrolled", mapOf("tester_code" to installation.testerCode))
        scheduleUpload(context)
        return installation
    }

    fun log(event: String, attributes: Map<String, String> = emptyMap(), error: Throwable? = null) {
        val payload = buildMap {
            attributes.forEach { (key, value) -> put(key.take(80), sanitizeDiagnosticValue(value)) }
            error?.let {
                put("error", it.javaClass.simpleName)
                it.message?.let { message -> put("message", sanitizeDiagnosticValue(message)) }
            }
        }
        eventStore.enqueue(
            BetaEvent(
                id = UUID.randomUUID().toString(),
                type = event.take(80),
                occurredAt = Instant.now(),
                payload = payload,
            ),
        )
        if (isEnrolled) scheduleUpload(context)
    }

    fun autoPreflight(): AutoPreflight {
        val current = installation ?: return AutoPreflight(false, "not_enrolled")
        return api.autoPreflight(current)
    }

    fun queueScheduleFeedback(
        status: String,
        selectedDate: String,
        sessionCount: Int,
        note: String?,
        detectedCohorts: Set<String>,
    ) {
        val sections = detectedCohorts.filter { it.startsWith("Section ", ignoreCase = true) }
        val groups = detectedCohorts.filter {
            it.startsWith("Group ", ignoreCase = true) || it.startsWith("PLC ", ignoreCase = true)
        }
        queue(
            "schedule",
            mapOf(
                "status" to status,
                "confirmed_at" to Instant.now().toString(),
                "selected_date" to selectedDate,
                "app_session_count" to sessionCount,
                "note" to note,
                "detected_sections" to sections,
                "detected_groups" to groups,
                "snapshot" to mapOf("cohort_labels" to detectedCohorts.sorted()),
            ),
        )
        uiPreferences.edit().putBoolean(scheduleFeedbackKey(selectedDate, sessionCount), true).apply()
    }

    fun hasScheduleFeedback(selectedDate: String, sessionCount: Int): Boolean =
        uiPreferences.getBoolean(scheduleFeedbackKey(selectedDate, sessionCount), false)

    fun queueAttendance(
        method: String,
        sessionId: String,
        sessionLabel: String,
        outcome: String,
        gateAllowed: Boolean?,
        gateReason: String?,
        lmsMarkable: Boolean?,
        evidence: LocationEvidence?,
        now: Instant = Instant.now(),
    ) {
        queue(
            "attendance",
            mapOf(
                "event_id" to UUID.randomUUID().toString(),
                "occurred_at" to now.toString(),
                "method" to method,
                "session_fingerprint" to sessionId,
                "session_label" to sessionLabel,
                "latitude" to evidence?.latitude,
                "longitude" to evidence?.longitude,
                "accuracy_m" to evidence?.accuracyMeters,
                "location_age_ms" to evidence?.let { Duration.between(it.observedAt, now).toMillis() },
                "gate_allowed" to gateAllowed,
                "gate_reason" to gateReason,
                "lms_markable" to lmsMarkable,
                "outcome" to outcome,
                "details" to mapOf("location_is_mock" to evidence?.isMock),
            ),
        )
    }

    fun submitReport(
        category: String,
        title: String,
        description: String,
        imageUris: List<Uri>,
    ): BetaReportResult {
        val current = installation ?: throw BetaApiException(401, "not_enrolled")
        val attachments = imageUris.take(4).map { uri -> readAttachment(uri) }
        return api.submitReport(
            installation = current,
            category = category,
            title = title,
            description = description,
            attachments = attachments,
            appContext = mapOf(
                "app_version" to BuildConfig.VERSION_NAME,
                "app_version_code" to BuildConfig.VERSION_CODE.toString(),
                "device_model" to Build.MODEL,
            ),
        )
    }

    fun flush(): Boolean {
        val current = installation ?: return true
        val events = eventStore.peek(100)
        if (events.isNotEmpty()) {
            api.uploadEvents(current, events, deviceInfo(), autoAttendanceEnabled())
            if (!eventStore.acknowledge(events.mapTo(mutableSetOf(), BetaEvent::id))) return false
        }
        requestStore.peek(20).forEach { request ->
            api.postQueued(current, request)
            if (!requestStore.acknowledge(request.id)) return false
        }
        return true
    }

    private fun queue(route: String, body: Map<String, Any?>) {
        requestStore.enqueue(BetaQueuedRequest(UUID.randomUUID().toString(), route, body))
        if (isEnrolled) scheduleUpload(context)
    }

    private fun scheduleFeedbackKey(selectedDate: String, sessionCount: Int): String =
        "schedule_feedback_${selectedDate}_$sessionCount"

    private fun readAttachment(uri: Uri): BetaReportAttachment {
        val contentType = when (context.contentResolver.getType(uri)) {
            "image/png" -> "image/png"
            else -> "image/jpeg"
        }
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not open a selected screenshot.")
        val output = ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                if (output.size() > MAX_ATTACHMENT_BYTES) {
                    throw IllegalArgumentException("Each screenshot must be 5 MB or smaller.")
                }
            }
        }
        return BetaReportAttachment(contentType, output.toByteArray())
    }

    private fun deviceInfo() = BetaDeviceInfo(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        androidVersion = Build.VERSION.RELEASE,
        appVersion = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE,
    )

    companion object {
        const val CONSENT_VERSION = "beta-2026-08-15"
        const val MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024
        private const val UPLOAD_WORK = "beta-telemetry-upload"

        fun create(context: Context, autoAttendanceEnabled: () -> Boolean): BetaManager {
            val plainPreferences = context.getSharedPreferences("beta_upload_queue", Context.MODE_PRIVATE)
            val preferences = AndroidBetaPreferences(plainPreferences)
            return BetaManager(
                context = context.applicationContext,
                installationStore = BetaInstallationStore(context),
                eventStore = JsonBetaEventStore(preferences),
                requestStore = JsonBetaRequestStore(preferences),
                api = BetaApiClient(BuildConfig.BETA_API_BASE),
                autoAttendanceEnabled = autoAttendanceEnabled,
            )
        }

        fun scheduleUpload(context: Context) {
            val request = OneTimeWorkRequestBuilder<BetaUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UPLOAD_WORK, ExistingWorkPolicy.KEEP, request)
        }
    }
}

class BetaUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val manager = (applicationContext as CompanionApplication).betaManager
        return runCatching { manager.flush() }
            .fold(
                onSuccess = { flushed -> if (flushed) Result.success() else Result.retry() },
                onFailure = { Result.retry() },
            )
    }
}

class BetaDiagnosticsLogger(
    private val local: DiagnosticsLogger,
    private val beta: BetaManager,
) : DiagnosticsLogger {
    override fun log(event: String, attributes: Map<String, String>, error: Throwable?) {
        local.log(event, attributes, error)
        runCatching { beta.log(event, attributes, error) }
    }
}

private class AndroidBetaPreferences(private val preferences: SharedPreferences) : BetaPreferences {
    override fun get(key: String): String? = preferences.getString(key, null)
    override fun put(key: String, value: String): Boolean = preferences.edit().putString(key, value).commit()
}
