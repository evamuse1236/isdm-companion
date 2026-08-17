package org.isdm.companion.platform

import java.io.IOException
import java.time.Instant
import java.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class BetaInstallation(
    val testerCode: String,
    val installationId: String,
    val installToken: String,
)

data class BetaDeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val appVersion: String,
    val appVersionCode: Int,
)

data class BetaEnrollment(
    val inviteCode: String,
    val section: String,
    val plc: String?,
    val consentVersion: String,
    val device: BetaDeviceInfo,
)

data class BetaProfile(
    val supportName: String?,
    val selfSection: String?,
    val selfPlc: String?,
    val detectedSections: Set<String>,
    val detectedGroups: Set<String>,
)

data class BetaProfileUpdate(
    val supportName: String?,
    val selfSection: String?,
    val selfPlc: String?,
    val detectedSections: Set<String>,
    val detectedGroups: Set<String>,
    val consentVersion: String,
    val confirmedAt: Instant,
)

data class AutoPreflight(val allowed: Boolean, val reason: String)

data class BetaEvent(
    val id: String,
    val type: String,
    val occurredAt: Instant,
    val payload: Map<String, String>,
)

data class BetaReportAttachment(
    val contentType: String,
    val bytes: ByteArray,
)

data class BetaReportResult(val reportId: String, val attachmentsUploaded: Int)

class BetaApiException(val statusCode: Int, val code: String) : IOException(code)

class BetaApiClient(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    fun enroll(enrollment: BetaEnrollment): BetaInstallation {
        val body = JSONObject()
            .put("invite_code", enrollment.inviteCode.trim())
            .put("consent_version", enrollment.consentVersion)
            .put("self_section", enrollment.section.trim())
            .putOptional("self_plc", enrollment.plc?.trim()?.takeIf(String::isNotEmpty))
            .putDevice(enrollment.device)
        val response = post("enroll", body)
        return BetaInstallation(
            testerCode = response.getString("tester_code"),
            installationId = response.getString("installation_id"),
            installToken = response.getString("install_token"),
        )
    }

    fun autoPreflight(installation: BetaInstallation): AutoPreflight {
        val response = post("auto-preflight", JSONObject(), installation, acceptedStatuses = setOf(200, 423))
        return AutoPreflight(response.optBoolean("allowed", false), response.optString("reason", "unknown"))
    }

    fun updateProfile(installation: BetaInstallation, update: BetaProfileUpdate): BetaProfile {
        val response = post(
            "profile",
            JSONObject()
                .putOptional("support_name", update.supportName?.trim()?.takeIf(String::isNotEmpty))
                .putOptional("self_section", update.selfSection?.trim()?.takeIf(String::isNotEmpty))
                .putOptional("self_plc", update.selfPlc?.trim()?.takeIf(String::isNotEmpty))
                .put("detected_sections", JSONArray(update.detectedSections.sorted()))
                .put("detected_groups", JSONArray(update.detectedGroups.sorted()))
                .put("consent_version", update.consentVersion)
                .put("confirmed_at", update.confirmedAt.toString()),
            installation,
        )
        return BetaProfile(
            supportName = response.optStringOrNull("support_name"),
            selfSection = response.optStringOrNull("self_section"),
            selfPlc = response.optStringOrNull("self_plc"),
            detectedSections = response.optStringSet("detected_sections"),
            detectedGroups = response.optStringSet("detected_groups"),
        )
    }

    fun deleteSupportName(installation: BetaInstallation) {
        post("profile-delete", JSONObject(), installation)
    }

    fun uploadEvents(
        installation: BetaInstallation,
        events: List<BetaEvent>,
        device: BetaDeviceInfo,
        autoAttendanceEnabled: Boolean,
    ) {
        require(events.isNotEmpty())
        val eventArray = JSONArray()
        events.take(MAX_EVENT_BATCH).forEach { event ->
            eventArray.put(
                JSONObject()
                    .put("event_id", event.id)
                    .put("event_type", event.type)
                    .put("occurred_at", event.occurredAt.toString())
                    .put("payload", JSONObject(event.payload)),
            )
        }
        post(
            "events",
            JSONObject()
                .put("events", eventArray)
                .putDevice(device)
                .put("auto_attendance_enabled", autoAttendanceEnabled),
            installation,
        )
    }

    fun postQueued(installation: BetaInstallation, request: BetaQueuedRequest) {
        post(request.route, JSONObject(request.body), installation)
    }

    fun submitReport(
        installation: BetaInstallation,
        category: String,
        title: String,
        description: String,
        attachments: List<BetaReportAttachment>,
        appContext: Map<String, String>,
    ): BetaReportResult {
        val attachmentArray = JSONArray()
        attachments.take(4).forEach { attachment ->
            attachmentArray.put(
                JSONObject()
                    .put("content_type", attachment.contentType)
                    .put("base64", Base64.getEncoder().encodeToString(attachment.bytes)),
            )
        }
        val response = post(
            "report",
            JSONObject()
                .put("category", category)
                .put("title", title)
                .put("description", description)
                .put("attachments", attachmentArray)
                .put("app_context", JSONObject(appContext)),
            installation,
        )
        return BetaReportResult(
            reportId = response.getString("report_id"),
            attachmentsUploaded = response.optInt("attachments_uploaded", 0),
        )
    }

    private fun post(
        route: String,
        body: JSONObject,
        installation: BetaInstallation? = null,
        acceptedStatuses: Set<Int> = setOf(200, 201),
    ): JSONObject {
        val request = Request.Builder()
            .url("$baseUrl/$route")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                if (installation != null) {
                    header("x-installation-id", installation.installationId)
                    header("x-install-token", installation.installToken)
                }
            }
            .build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(responseText) }.getOrElse { JSONObject() }
            if (response.code !in acceptedStatuses) {
                throw BetaApiException(response.code, json.optString("error", "http_${response.code}"))
            }
            return json
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_EVENT_BATCH = 100
    }
}

private fun JSONObject.putDevice(device: BetaDeviceInfo): JSONObject =
    put("manufacturer", device.manufacturer)
        .put("model", device.model)
        .put("android_version", device.androidVersion)
        .put("app_version", device.appVersion)
        .put("app_version_code", device.appVersionCode)

private fun JSONObject.putOptional(key: String, value: String?): JSONObject = apply {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun JSONObject.optStringSet(key: String): Set<String> {
    val values = optJSONArray(key) ?: return emptySet()
    return buildSet {
        repeat(values.length()) { index -> values.optString(index).takeIf(String::isNotBlank)?.let(::add) }
    }
}
