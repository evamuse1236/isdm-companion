package org.isdm.companion.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.isdm.companion.engine.CalendarEvent
import org.isdm.companion.engine.AssessmentItem
import org.isdm.companion.engine.AttendanceSummary
import org.isdm.companion.engine.ClassroomDetail
import org.isdm.companion.engine.Credentials
import org.isdm.companion.engine.FacultyDirectory
import org.isdm.companion.engine.FacultyProfile
import org.isdm.companion.engine.Identity
import org.isdm.companion.engine.LmsCourse
import org.isdm.companion.engine.Markability
import org.isdm.companion.engine.ReadingItem
import org.isdm.companion.domain.parseLmsTime
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * LMS implementation backed by OkHttp and Jsoup.
 *
 * The LMS is a Drupal site rather than a JSON API. This class deliberately keeps the awkward
 * details (dynamic form fields, redirects, cookies and HTML tables) behind [LmsGateway].
 * Requests are made with redirects disabled so a POST redirect can be treated exactly as the
 * LMS browser client treats it: 301/302/303 become GET, while 307/308 preserve the method.
 */
class RealLmsAdapter(
    private val defaultEmail: String? = null,
    private val defaultPassword: String? = null,
    baseUrl: String = DEFAULT_BASE_URL,
    private val lmsDiagnosticReporter: LmsDiagnosticReporter = NoopLmsDiagnosticReporter,
) : LmsGateway, FacultyDirectory {
    private val baseUrl: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val cookies = SessionCookieJar()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val loginMutex = Mutex()

    @Volatile
    private var uid: String? = null

    @Volatile
    private var displayName: String? = null

    @Volatile
    private var activeCredentials: Credentials? = null

    override suspend fun login(credentials: Credentials): Identity = loginMutex.withLock {
        uid?.let { return@withLock Identity(it, displayName) }
        activeCredentials = credentials
        doLogin(credentials)
    }

    /** Convenience overload for tests and callers that already have the LMS wire dates. */
    suspend fun login(): Identity = login(defaultCredentials())

    /**
     * Return a freshly validated LMS session in Set-Cookie form for the in-app reading browser.
     * The browser never needs the user's password; it receives only the same authenticated
     * session cookies already used by this adapter.
     */
    suspend fun browserCookieHeaders(credentials: Credentials): List<String> {
        activeCredentials = credentials
        login(credentials)
        authed("/home")
        return cookies.snapshotFor(baseUrl).map(Cookie::toString)
    }

    /** Resolve the LMS document metadata to its temporary, directly downloadable PDF URL. */
    suspend fun readingDownloadUrl(credentials: Credentials, sourceUrl: String): String {
        val source = sourceUrl.toHttpUrlOrNull()
            ?.takeIf { it.host.equals(baseUrl.host, ignoreCase = true) }
            ?: throw LmsProtocolException("The reading URL is not on the LMS.")
        val readingId = source.queryParameter("vid")
            ?.takeIf { it.matches(NUMERIC_ID) }
            ?: throw LmsProtocolException("The reading URL has no numeric document id.")
        val courseId = source.queryParameter("sid")?.takeIf { it.matches(NUMERIC_ID) }.orEmpty()

        activeCredentials = credentials
        val identity = login(credentials)
        val metadataUrl = baseUrl.resolve("/api/downloadcontent")!!.newBuilder()
            .addQueryParameter("nid", readingId)
            .addQueryParameter("format", "native")
            .addQueryParameter("auth", "true")
            .addQueryParameter("uid", identity.uid)
            .addQueryParameter("courseid", courseId)
            .addQueryParameter("api_version", "1")
            .build()
        val metadata = authed(metadataUrl.toString())
        val directUrl = runCatching {
            JSONArray(metadata.body)
                .getJSONObject(0)
                .getJSONObject("videourl")
                .getString("native")
        }.getOrNull()
            ?.toHttpUrlOrNull()
            ?.takeIf { it.isHttps }
            ?.toString()
            ?: throw LmsProtocolException("The LMS did not provide a secure PDF download URL.")
        return directUrl
    }

    override suspend fun calendar(start: LocalDate, endExclusive: LocalDate): List<CalendarEvent> =
        calendar(start.toString(), endExclusive.toString())

    /** Convenience overload retaining the wire-level date shape used by the desktop client. */
    suspend fun calendar(start: String, end: String): List<CalendarEvent> {
        val page = authed(
            "/calendar/json?start=$start&end=$end&_=${System.currentTimeMillis()}",
        )
        val array = try {
            JSONArray(page.body)
        } catch (error: Exception) {
            throw LmsProtocolException("Calendar response was not JSON.", error)
        }

        return buildList {
            for (index in 0 until array.length()) {
                val event = array.optJSONObject(index) ?: continue
                add(
                    CalendarEvent(
                        nid = nullableString(event, "nid").orEmpty(),
                        title = event.optString("title", ""),
                        url = nullableString(event, "url"),
                        start = event.optString("start", ""),
                        end = nullableString(event, "end"),
                        subject = nullableString(event, "subject"),
                        trainers = nullableString(event, "trainers"),
                    ),
                )
            }
        }
    }

    override suspend fun courses(): List<LmsCourse> {
        val page = authed("/show/all/courses")
        return parseCourses(page.body, baseUrl.toString())
    }

    override suspend fun readings(course: LmsCourse): List<ReadingItem> {
        requireNumericId(course.catId, "course")
        val coursePage = authed("/course/details?cat_id=${course.catId}")
        val sections = parseReadingSections(coursePage.body, course, baseUrl.toString())
        return buildList {
            for (section in sections) {
                val page = authed(
                    "/course/details?cat_id=${course.catId}&course_id=${section.sid}",
                )
                addAll(parseReadingItems(page.body, course, section, baseUrl.toString()))
            }
        }.distinctBy { it.vid }
    }

    override suspend fun assessments(): List<AssessmentItem> {
        val drafts = parseAssessmentTasks(authed("/my-activities").body, baseUrl.toString())
        val sectionPages = mutableMapOf<Pair<String, String>, String>()
        return buildList {
            for (draft in drafts) {
                val activityPage = authed(draft.submissionUrl).body
                val dates = parseAssessmentFrameUrl(activityPage, baseUrl.toString())
                    ?.let { frameUrl -> parseAssessmentDates(authed(frameUrl).body) }
                    ?: AssessmentDates(dueDate = null, endDate = null)
                val sectionKey = draft.courseId to draft.sectionId
                var sectionPage = sectionPages[sectionKey]
                if (sectionPage == null) {
                    sectionPage = authed(
                        "/course/details?cat_id=${draft.courseId}&course_id=${draft.sectionId}",
                    ).body
                    sectionPages[sectionKey] = sectionPage
                }
                val resource = parseAssessmentResource(
                    html = sectionPage,
                    submissionUrl = draft.submissionUrl,
                    baseUrl = baseUrl.toString(),
                )
                add(
                    AssessmentItem(
                        id = draft.id,
                        title = draft.title,
                        status = draft.status,
                        dueDate = dates.dueDate,
                        endDate = dates.endDate,
                        submissionUrl = draft.submissionUrl,
                        resourceTitle = resource?.title,
                        resourceUrl = resource?.sourceUrl,
                    ),
                )
            }
        }
    }

    override suspend fun classroom(nid: String): ClassroomDetail {
        requireNumericSessionId(nid)
        val page = authed("/classroom/${encodePathSegment(nid)}/view")
        val table = parseLabelTable(page.body)
        val markedRaw = pick(table, "Attendance Marked (For me)", "Attendance Marked")
        return ClassroomDetail(
            nid = nid,
            title = pick(table, "Title"),
            room = pick(table, "Location", "Venue"),
            trainer = pick(table, "Trainers", "Trainer"),
            course = pick(table, "Courses", "Course"),
            marked = markedRaw?.equals("yes", ignoreCase = true) == true,
            status = pick(table, "Status"),
            end = parseLmsTime(pick(table, "End Date & Time", "End Date and Time", "End Time")),
        )
    }

    override suspend fun attendanceSummary(): AttendanceSummary {
        val end = Instant.now().epochSecond
        val start = end - ATTENDANCE_HISTORY_SECONDS
        val url = baseUrl.resolve("/api/executereport")!!.newBuilder()
            .addQueryParameter("report", "student-dashboard-user-classroom-session-summary")
            .addQueryParameter("start_time", start.toString())
            .addQueryParameter("end_time", end.toString())
            .build()
        val page = authed(url.toString())
        return parseAttendanceSummary(page.body)
    }

    override suspend fun facultyProfiles(course: LmsCourse): List<FacultyProfile> {
        requireNumericId(course.catId, "course")
        val coursePage = authed("/course/details?cat_id=${course.catId}")
        return parseFacultyProfiles(coursePage.body, course, baseUrl.toString())
    }

    override suspend fun markability(): Map<String, Markability> {
        val page = authed("/manage/classroom/attendance")
        val document = Jsoup.parse(page.body)
        val result = linkedMapOf<String, Markability>()

        // Both mark-attend and mark-attend-disabled contain the uid attribute. The exact
        // class token, not a substring check, is the LMS's source of truth for markability.
        for (element in document.select("div[uid][id]")) {
            val nid = element.attr("id")
            if (!nid.matches(NUMERIC_ID)) continue
            val classes = element.classNames()
            if ("mark-attend" !in classes && "mark-attend-disabled" !in classes) continue
            result[nid] = Markability(
                markable = "mark-attend" in classes,
                uid = element.attr("uid").takeIf { it.isNotBlank() },
            )
        }
        return result
    }

    override suspend fun markPresent(nid: String): ClassroomDetail {
        requireNumericSessionId(nid)
        val identity = uid?.let { Identity(it, displayName) } ?: login(defaultCredentials())
        val payload = JSONObject()
            .put("nid", nid)
            .put("uid", identity.uid)
            .put("status", "present")
            .toString()

        val page = authed(
            path = "/api/mark/classroomsession/attendance",
            method = "POST",
            headers = mapOf(
                "Content-Type" to JSON_MEDIA_TYPE.toString(),
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to baseUrl.resolve("/manage/classroom/attendance").toString(),
            ),
            // The desktop client sends exactly application/json. The String overload adds a
            // charset parameter, so use bytes to keep the wire header identical.
            body = payload.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA_TYPE),
        )
        if (page.status >= 400) {
            throw LmsHttpException(page.status, page.url)
        }

        // The POST response body is not authoritative. The LMS sometimes returns success even
        // when the attendance window closed between the list read and this request.
        return classroom(nid)
    }

    override fun resetSession() {
        uid = null
        displayName = null
        activeCredentials = null
        cookies.clear()
    }

    fun reset() = resetSession()

    private suspend fun doLogin(credentials: Credentials): Identity {
        if (credentials.email.isBlank() || credentials.password.isBlank()) {
            throw LmsAuthenticationException("LMS email and password are required.")
        }

        // A login always starts a clean session. This also prevents a stale cookie from making a
        // failed password look like a successful login.
        cookies.clear()
        uid = null
        displayName = null

        val loginPage = requestRaw("/user/login")
        val form = findLoginForm(loginPage.body)
            ?: throw LmsProtocolException("Could not find the LMS login form.")

        val values = linkedMapOf<String, String>()
        var usernameField: String? = null
        var passwordField: String? = null
        for (input in form.select("input[name]")) {
            val name = input.attr("name").trim()
            if (name.isEmpty()) continue
            when (input.attr("type").lowercase()) {
                "password" -> if (passwordField == null) passwordField = name
                "text", "email" -> if (usernameField == null) usernameField = name
                "hidden", "submit" -> values[name] = input.attr("value")
            }
        }

        val userField = usernameField
            ?: throw LmsProtocolException("The LMS login form has no email field.")
        val passField = passwordField
            ?: throw LmsProtocolException("The LMS login form has no password field.")
        values[userField] = credentials.email
        values[passField] = credentials.password

        // Drupal's form token is mirrored into the secure-token field on this LMS.
        values["form_build_id"]?.let { buildId ->
            if (values.containsKey("st")) values["st"] = buildId
        }

        val action = form.attr("action").trim().ifEmpty { "/user/login" }
        val loginResult = requestRaw(
            path = action,
            method = "POST",
            headers = mapOf("Content-Type" to FORM_MEDIA_TYPE.toString()),
            body = FormBody.Builder().apply {
                values.forEach { (key, value) -> add(key, value) }
            }.build(),
        )

        if (looksLoggedOut(loginResult)) {
            throw LmsAuthenticationException(loginError(loginResult.body))
        }
        captureIdentity(loginResult.body)

        if (uid == null) {
            val home = requestRaw("/home")
            if (looksLoggedOut(home)) {
                throw LmsAuthenticationException(loginError(home.body))
            }
            captureIdentity(home.body)
        }

        val identity = uid?.let { Identity(it, displayName) }
            ?: throw LmsAuthenticationException("Logged in but could not determine the user id.")
        return identity
    }

    private suspend fun ensureLoggedIn() {
        if (uid == null) login(defaultCredentials())
    }

    private fun defaultCredentials(): Credentials = activeCredentials ?: Credentials(
        email = defaultEmail.orEmpty(),
        password = defaultPassword.orEmpty(),
    )

    /** Execute once, re-authenticate once when the LMS sends its login form, then fail. */
    private suspend fun authed(
        path: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
    ): ResponseData {
        ensureLoggedIn()
        var response = requestRaw(path, method, headers, body)
        if (!looksLoggedOut(response)) {
            if (response.status >= 400) throw LmsHttpException(response.status, response.url)
            return response
        }

        val credentials = activeCredentials ?: defaultCredentials()
        resetSession()
        login(credentials)
        response = requestRaw(path, method, headers, body)
        if (looksLoggedOut(response)) {
            throw LmsAuthenticationException("Still logged out after re-authenticating ($path).")
        }
        if (response.status >= 400) throw LmsHttpException(response.status, response.url)
        return response
    }

    private suspend fun requestRaw(
        path: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
    ): ResponseData {
        var url = urlFor(path)
        var currentMethod = method.uppercase()
        var currentBody = body
        var currentHeaders = headers.toMutableMap()

        repeat(MAX_REDIRECTS + 1) {
            val builder = Request.Builder().url(url)
            currentHeaders.forEach { (key, value) -> builder.header(key, value) }
            if (currentMethod == "GET" || currentMethod == "HEAD") {
                builder.method(currentMethod, null)
            } else {
                builder.method(currentMethod, currentBody ?: EMPTY_BODY)
            }

            val response = try {
                withContext(Dispatchers.IO) { client.newCall(builder.build()).execute() }
            } catch (error: IOException) {
                lmsDiagnosticReporter.record(lmsEndpointLabel(url.encodedPath), null, "network")
                throw LmsException("LMS network request failed for $url.", error)
            }

            val status = response.code
            if (status >= 400) {
                lmsDiagnosticReporter.record(lmsEndpointLabel(url.encodedPath), status, "http")
            }
            val location = response.header("Location")
            if (status in REDIRECT_STATUSES && location != null) {
                val next = response.request.url.resolve(location)
                response.close()
                url = next ?: throw LmsProtocolException("LMS returned an invalid redirect.")
                if (currentMethod == "POST" && status !in PRESERVE_METHOD_REDIRECTS) {
                    currentMethod = "GET"
                    currentBody = null
                    currentHeaders = currentHeaders
                        .filterKeys { !it.equals("Content-Type", ignoreCase = true) }
                        .toMutableMap()
                }
                return@repeat
            }

            val text = response.body?.string().orEmpty()
            val responseUrl = response.request.url.toString()
            val responseHeaders = response.headers.toMultimap()
                .mapValues { (_, values) -> values.joinToString(",") }
            response.close()
            return ResponseData(status, responseUrl, responseHeaders, text)
        }

        throw LmsProtocolException("Too many redirects starting at $path.")
    }

    private fun urlFor(path: String): HttpUrl = baseUrl.resolve(path)
        ?: throw LmsProtocolException("Invalid LMS URL: $path")

    private fun findLoginForm(html: String): Element? {
        val document = Jsoup.parse(html)
        return document.select("form").firstOrNull { form ->
            form.select("input[name=form_id]").any { it.attr("value") == "user_login" }
        } ?: document.select("form").firstOrNull {
            it.select("input[type=password]").isNotEmpty()
        }
    }

    private fun looksLoggedOut(page: ResponseData): Boolean {
        val loginForm = Regex(
            "name\\s*=\\s*[\"']form_id[\"'][^>]*value\\s*=\\s*[\"']user_login",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(page.body) || Regex(
            "value\\s*=\\s*[\"']user_login[\"'][^>]*name\\s*=\\s*[\"']form_id",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(page.body)
        return loginForm || page.url.substringBefore('?').endsWith("/user/login")
    }

    private fun loginError(html: String): String {
        val message = Jsoup.parse(html).select(".messages, .error").text().trim()
        return if (message.isBlank()) {
            "LMS rejected the login; check the email and password."
        } else {
            "LMS rejected the login: ${message.take(200)}"
        }
    }

    private fun captureIdentity(html: String) {
        val uidMatch = Regex("/user/(\\d+)/edit/chgpwd", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("[?&]uid=(\\d+)", RegexOption.IGNORE_CASE).find(html)
        if (uidMatch != null) uid = uidMatch.groupValues[1]

        val name = Jsoup.parse(html).select("div.user-name").text().trim()
        if (name.isNotBlank()) displayName = name
    }

    private fun parseLabelTable(html: String): Map<String, String> {
        val table = linkedMapOf<String, String>()
        for (row in Jsoup.parse(html).select("tr")) {
            val cells = row.select("td")
            if (cells.size < 2) continue
            val label = cells.first()?.text()?.trim().orEmpty()
            val value = cells.last()?.text()?.trim().orEmpty()
            if (label.isNotBlank()) table[label] = value
        }
        return table
    }

    private fun pick(table: Map<String, String>, vararg labels: String): String? {
        for (label in labels) {
            val key = table.keys.firstOrNull { it.equals(label, ignoreCase = true) }
            val value = key?.let { table[it] }
            if (!value.isNullOrBlank() && value != "-") return value
        }
        return null
    }

    private fun encodePathSegment(value: String): String =
        value.replace("/", "%2F").replace("?", "%3F")

    private fun requireNumericSessionId(nid: String) {
        requireNumericId(nid, "session")
    }

    private fun requireNumericId(value: String, label: String) {
        if (!value.matches(NUMERIC_ID)) throw LmsProtocolException("A numeric LMS $label id is required.")
    }

    private fun nullableString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.optString(key).takeIf { it.isNotBlank() }
    }

    private data class ResponseData(
        val status: Int,
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private class SessionCookieJar : CookieJar {
        private val lock = Any()
        private val values = linkedMapOf<String, Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(lock) {
                val now = System.currentTimeMillis()
                for (cookie in cookies) {
                    val key = key(cookie)
                    if (cookie.persistent && cookie.expiresAt <= now) values.remove(key)
                    else values[key] = cookie
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
            val now = System.currentTimeMillis()
            values.values.removeAll { cookie -> cookie.persistent && cookie.expiresAt <= now }
            values.values.filter { it.matches(url) }
        }

        fun snapshotFor(url: HttpUrl): List<Cookie> = loadForRequest(url).toList()

        fun clear() = synchronized(lock) { values.clear() }

        private fun key(cookie: Cookie): String =
            "${cookie.name};${cookie.domain};${cookie.path}"
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://lms.isdm.org.in"
        const val MAX_REDIRECTS = 6
        const val ATTENDANCE_HISTORY_SECONDS = 365L * 24L * 60L * 60L
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        val PRESERVE_METHOD_REDIRECTS = setOf(307, 308)
        val NUMERIC_ID = Regex("\\d+")
        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}

internal fun parseAttendanceSummary(json: String): AttendanceSummary {
    val rows = try {
        JSONArray(json)
    } catch (error: Exception) {
        throw LmsProtocolException("The LMS attendance report was not JSON.", error)
    }
    val counts = buildMap {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val title = row.optString("title").trim().lowercase(Locale.ROOT)
            val count = row.optInt("count", -1)
            if (title.isNotBlank() && count >= 0) put(title, count)
        }
    }
    fun count(title: String): Int = counts[title]
        ?: throw LmsProtocolException("The LMS $title attendance total was missing.")

    val total = count("total")
    val present = count("present")
    val absent = count("absent")
    val notMarked = count("not marked")
    if (total != present + absent + notMarked) {
        throw LmsProtocolException("The LMS attendance totals did not add up.")
    }
    val percentage = if (total == 0) {
        BigDecimal.ZERO
    } else {
        BigDecimal(present)
            .multiply(BigDecimal("100"))
            .divide(BigDecimal(total), 2, RoundingMode.HALF_UP)
    }

    return AttendanceSummary(
        total = total,
        present = present,
        absent = absent,
        notMarked = notMarked,
        presentPercentage = percentage,
    )
}

fun interface LmsDiagnosticReporter {
    fun record(endpointLabel: String, httpStatus: Int?, failureType: String)
}

private object NoopLmsDiagnosticReporter : LmsDiagnosticReporter {
    override fun record(endpointLabel: String, httpStatus: Int?, failureType: String) = Unit
}

internal fun lmsEndpointLabel(path: String): String = when {
    path == "/user/login" -> "login"
    path == "/home" -> "home"
    path == "/calendar/json" -> "calendar"
    path == "/show/all/courses" -> "courses"
    path == "/course/details" -> "course_details"
    path.startsWith("/classroom/") -> "classroom"
    path == "/api/executereport" -> "attendance_summary"
    path == "/manage/classroom/attendance" -> "markability"
    path == "/api/mark/classroomsession/attendance" -> "mark_present"
    path == "/api/downloadcontent" -> "reading_download"
    else -> "other"
}
