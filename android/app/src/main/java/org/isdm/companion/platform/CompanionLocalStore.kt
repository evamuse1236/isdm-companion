package org.isdm.companion.platform

import android.content.Context
import org.isdm.companion.engine.CachedSchedule
import org.isdm.companion.engine.AssessmentItem
import org.isdm.companion.engine.CompanionCacheStore
import org.isdm.companion.engine.CompanionSession
import org.isdm.companion.engine.FacultyProfile
import org.isdm.companion.engine.LmsReadingProgress
import org.isdm.companion.engine.ReadingDoneStore
import org.isdm.companion.engine.ReadingItem
import org.isdm.companion.engine.SessionState
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.security.MessageDigest

class CompanionLocalStore(context: Context) : ReadingDoneStore, CompanionCacheStore {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private var accountPrefix: String? = null

    override fun selectAccount(accountId: String) = synchronized(lock) {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(accountId.trim().lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        accountPrefix = digest.take(16)
    }

    override fun load(): Set<String> = synchronized(lock) {
        val key = accountKey(KEY_DONE) ?: return@synchronized emptySet()
        preferences.getStringSet(key, emptySet()).orEmpty().toSet()
    }

    override fun setDone(readingId: String, done: Boolean) = synchronized(lock) {
        val key = accountKey(KEY_DONE) ?: return@synchronized
        val values = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        if (done) values += readingId else values -= readingId
        preferences.edit().putStringSet(key, values).apply()
    }

    override fun loadSchedule(): CachedSchedule? = synchronized(lock) {
        val key = accountKey(KEY_SCHEDULE) ?: return@synchronized null
        val raw = preferences.getString(key, null) ?: return@synchronized null
        runCatching {
            val root = JSONObject(raw)
            val sessions = root.getJSONArray("sessions").mapObjects(::decodeSession)
            CachedSchedule(
                start = LocalDate.parse(root.getString("start")),
                endExclusive = LocalDate.parse(root.getString("endExclusive")),
                sessions = sessions,
                syncedAt = Instant.parse(root.getString("syncedAt")),
            )
        }.getOrNull()
    }

    override fun saveSchedule(schedule: CachedSchedule) = synchronized(lock) {
        val key = accountKey(KEY_SCHEDULE) ?: return@synchronized
        val root = JSONObject()
            .put("start", schedule.start.toString())
            .put("endExclusive", schedule.endExclusive.toString())
            .put("syncedAt", schedule.syncedAt.toString())
            .put("sessions", JSONArray().apply { schedule.sessions.forEach { put(encodeSession(it)) } })
        preferences.edit().putString(key, root.toString()).apply()
    }

    override fun loadReadings(): List<ReadingItem> = synchronized(lock) {
        val key = accountKey(KEY_READINGS) ?: return@synchronized emptyList()
        val raw = preferences.getString(key, null) ?: return@synchronized emptyList()
        runCatching { JSONArray(raw).mapObjects(::decodeReading) }.getOrDefault(emptyList())
    }

    override fun saveReadings(readings: List<ReadingItem>) = synchronized(lock) {
        val key = accountKey(KEY_READINGS) ?: return@synchronized
        val array = JSONArray().apply { readings.forEach { put(encodeReading(it.copy(done = false))) } }
        preferences.edit().putString(key, array.toString()).apply()
    }

    override fun loadAssessments(): List<AssessmentItem> = synchronized(lock) {
        val key = accountKey(KEY_ASSESSMENTS) ?: return@synchronized emptyList()
        val raw = preferences.getString(key, null) ?: return@synchronized emptyList()
        runCatching { JSONArray(raw).mapObjects(::decodeAssessment) }.getOrDefault(emptyList())
    }

    override fun saveAssessments(assessments: List<AssessmentItem>) = synchronized(lock) {
        val key = accountKey(KEY_ASSESSMENTS) ?: return@synchronized
        val array = JSONArray().apply { assessments.forEach { put(encodeAssessment(it)) } }
        preferences.edit().putString(key, array.toString()).apply()
    }

    override fun loadFacultyProfiles(): List<FacultyProfile> = synchronized(lock) {
        val key = accountKey(KEY_FACULTY_PROFILES) ?: return@synchronized emptyList()
        val raw = preferences.getString(key, null) ?: return@synchronized emptyList()
        runCatching { JSONArray(raw).mapObjects(::decodeFacultyProfile) }.getOrDefault(emptyList())
    }

    override fun saveFacultyProfiles(profiles: List<FacultyProfile>) = synchronized(lock) {
        val key = accountKey(KEY_FACULTY_PROFILES) ?: return@synchronized
        val array = JSONArray().apply { profiles.forEach { put(encodeFacultyProfile(it)) } }
        preferences.edit().putString(key, array.toString()).apply()
    }

    private fun accountKey(base: String): String? = accountPrefix?.let { "$it:$base" }

    private fun encodeSession(value: CompanionSession) = JSONObject()
        .putNullable("nid", value.nid)
        .putNullable("eventNid", value.eventNid)
        .put("name", value.name)
        .putNullable("cohort", value.cohort)
        .putNullable("sessionNumber", value.sessionNumber)
        .put("start", value.start.toString())
        .put("end", value.end.toString())
        .putNullable("subject", value.subject)
        .putNullable("trainer", value.trainer)
        .putNullable("room", value.room)
        .putNullable("floor", value.floor)
        .putNullable("floorLabel", value.floorLabel)
        .put("marked", value.marked)
        .put("markable", value.markable)
        .put("state", value.state.name)
        .putNullable("lateAfter", value.lateAfter?.toString())

    private fun decodeSession(value: JSONObject) = CompanionSession(
        nid = value.nullableString("nid"),
        eventNid = value.nullableString("eventNid"),
        name = value.getString("name"),
        cohort = value.nullableString("cohort"),
        sessionNumber = value.nullableInt("sessionNumber"),
        start = Instant.parse(value.getString("start")),
        end = Instant.parse(value.getString("end")),
        subject = value.nullableString("subject"),
        trainer = value.nullableString("trainer"),
        room = value.nullableString("room"),
        floor = value.nullableDouble("floor"),
        floorLabel = value.nullableString("floorLabel"),
        marked = value.optBoolean("marked"),
        markable = value.optBoolean("markable"),
        state = runCatching { SessionState.valueOf(value.getString("state")) }.getOrDefault(SessionState.UPCOMING),
        lateAfter = value.nullableString("lateAfter")?.let(Instant::parse),
    )

    private fun encodeReading(value: ReadingItem) = JSONObject()
        .put("vid", value.vid)
        .put("sid", value.sid)
        .put("cid", value.cid)
        .put("catId", value.catId)
        .put("title", value.title)
        .put("courseName", value.courseName)
        .put("sectionName", value.sectionName)
        .put("sourceUrl", value.sourceUrl)
        .putNullable("sessionNumber", value.sessionNumber)
        .put("progress", value.progress.name)
        .put("mandatory", value.mandatory)

    private fun decodeReading(value: JSONObject) = ReadingItem(
        vid = value.getString("vid"),
        sid = value.getString("sid"),
        cid = value.getString("cid"),
        catId = value.getString("catId"),
        title = value.getString("title"),
        courseName = value.getString("courseName"),
        sectionName = value.getString("sectionName"),
        sourceUrl = value.getString("sourceUrl"),
        sessionNumber = value.nullableInt("sessionNumber"),
        progress = runCatching { LmsReadingProgress.valueOf(value.getString("progress")) }
            .getOrDefault(LmsReadingProgress.UNKNOWN),
        mandatory = value.optBoolean("mandatory"),
    )

    private fun encodeAssessment(value: AssessmentItem) = JSONObject()
        .put("id", value.id)
        .put("title", value.title)
        .put("status", value.status)
        .putNullable("dueDate", value.dueDate?.toString())
        .putNullable("endDate", value.endDate?.toString())
        .put("submissionUrl", value.submissionUrl)
        .putNullable("resourceTitle", value.resourceTitle)
        .putNullable("resourceUrl", value.resourceUrl)

    private fun decodeAssessment(value: JSONObject) = AssessmentItem(
        id = value.getString("id"),
        title = value.getString("title"),
        status = value.getString("status"),
        dueDate = value.nullableString("dueDate")?.let(LocalDate::parse),
        endDate = value.nullableString("endDate")?.let(LocalDate::parse),
        submissionUrl = value.getString("submissionUrl"),
        resourceTitle = value.nullableString("resourceTitle"),
        resourceUrl = value.nullableString("resourceUrl"),
    )

    private fun encodeFacultyProfile(value: FacultyProfile) = JSONObject()
        .put("courseCatId", value.courseCatId)
        .put("courseName", value.courseName)
        .put("displayName", value.displayName)
        .put("itemTitle", value.itemTitle)
        .put("sourceUrl", value.sourceUrl)
        .putNullable("photoUrl", value.photoUrl)
        .putNullable("roleTitle", value.roleTitle)
        .putNullable("email", value.email)
        .putNullable("bio", value.bio)

    private fun decodeFacultyProfile(value: JSONObject) = FacultyProfile(
        courseCatId = value.getString("courseCatId"),
        courseName = value.getString("courseName"),
        displayName = value.getString("displayName"),
        itemTitle = value.getString("itemTitle"),
        sourceUrl = value.getString("sourceUrl"),
        photoUrl = value.nullableString("photoUrl"),
        roleTitle = value.nullableString("roleTitle"),
        email = value.nullableString("email"),
        bio = value.nullableString("bio"),
    )

    private companion object {
        const val FILE_NAME = "companion_local_data"
        const val KEY_DONE = "reading_done_ids"
        const val KEY_SCHEDULE = "schedule_cache"
        const val KEY_READINGS = "readings_cache"
        const val KEY_ASSESSMENTS = "assessments_cache"
        const val KEY_FACULTY_PROFILES = "faculty_profiles_cache"
    }
}

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)

private fun JSONObject.nullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else getInt(key)

private fun JSONObject.nullableDouble(key: String): Double? =
    if (!has(key) || isNull(key)) null else getDouble(key)

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    buildList { for (index in 0 until length()) add(transform(getJSONObject(index))) }
