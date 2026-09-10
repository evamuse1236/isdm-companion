package org.isdm.companion.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.time.ZoneId
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
    private val now: () -> Instant = Instant::now,
    private val beforeRequest: suspend () -> Unit = {},
) : LmsGateway, FacultyDirectory {
    private val baseUrl: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val cookies = SessionCookieJar()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val loginMutex = Mutex()
    private val sessionRevision = AtomicLong()
    private val contentPages = ConcurrentHashMap<String, String>()
    private val contentLocks = ConcurrentHashMap<String, Mutex>()
    private val contentPermits = Semaphore(4)

    override fun invalidateContentCache() { contentPages.clear() }

    private suspend fun contentPage(path: String): String =
        contentLocks.getOrPut(path) { Mutex() }.withLock {
            contentPages[path] ?: contentPermits.withPermit { authed(path).body }
                .also { contentPages[path] = it }
        }

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

    /** Convenience overload for callers that already hold ISO date query values. */
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
        val coursePage = contentPage("/course/details?cat_id=${course.catId}")
        val courseOutline = parseCourseOutlineLink(coursePage, course, baseUrl.toString())
        val sections = parseReadingSections(coursePage, course, baseUrl.toString())
        return buildList {
            for (section in sections) {
                val page = contentPage("/course/details?cat_id=${course.catId}&course_id=${section.sid}")
                addAll(
                    parseReadingItems(page, course, section, baseUrl.toString()).map { reading ->
                        reading.copy(
                            courseOutlineTitle = courseOutline?.title,
                            courseOutlineUrl = courseOutline?.sourceUrl,
                        )
                    },
                )
            }
        }.distinctBy { it.vid }
    }

    override suspend fun assessments(): List<AssessmentItem> = coroutineScope {
        val drafts = listOf(0, 3).map { status ->
            async { parseAssessmentTasks(authed("/my-activities?status=$status").body, baseUrl.toString()) }
        }.awaitAll().flatten().distinctBy { it.id }
        val permits = Semaphore(3)
        drafts.map { draft -> async { permits.withPermit { assessment(draft) } } }.awaitAll()
    }

    private suspend fun assessment(draft: AssessmentTaskDraft): AssessmentItem {
        var dates = AssessmentDates(null, null)
        var frameUrl: String? = null
        var resource: AssessmentResource? = null
        var notice: String? = if (draft.submissionUrl.isBlank()) "The LMS has not opened this activity yet." else null
        if (draft.submissionUrl.isNotBlank()) {
            try {
                val activityPage = contentPage(draft.submissionUrl)
                frameUrl = parseAssessmentFrameUrl(activityPage, baseUrl.toString())
                if (frameUrl == null) {
                    assessmentLoaderUrl(draft.submissionUrl)?.let { loader ->
                        frameUrl = parseAssessmentFrameUrl(contentPage(loader), baseUrl.toString())
                    }
                }
                if (frameUrl != null) dates = parseAssessmentDates(contentPage(frameUrl!!))
                if (dates.dueDate == null) notice = "Deadline not verified; check the LMS activity."
                resource = parseAssessmentResource(
                    contentPage("/course/details?cat_id=${draft.courseId}&course_id=${draft.sectionId}"),
                    draft.title, baseUrl.toString(),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is org.isdm.companion.engine.AuthenticationFailure ||
                    error is org.isdm.companion.engine.CompanionAccessException) throw error
                notice = "Activity details could not refresh; check the LMS."
            }
        }
        return AssessmentItem(
            id = draft.id, title = draft.title, status = draft.status,
            dueDate = dates.dueDate ?: draft.dueDate, endDate = dates.endDate,
            submissionUrl = frameUrl ?: draft.submissionUrl,
            resourceTitle = resource?.title, resourceUrl = resource?.sourceUrl,
            datesVerified = dates.dueDate != null, detailNotice = notice,
        )
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
        val end = now().epochSecond
        val start = end - ATTENDANCE_HISTORY_SECONDS
        val summaries = attendanceReportWindows(start, end).map { window ->
            attendanceSummary(window.first, window.last)
        }
        return combineAttendanceSummaries(summaries)
    }

    private suspend fun attendanceSummary(start: Long, end: Long): AttendanceSummary {
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
        val coursePage = contentPage("/course/details?cat_id=${course.catId}")
        return parseFacultyProfiles(coursePage, course, baseUrl.toString())
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

        try {
            authed(
                path = "/api/mark/classroomsession/attendance",
                method = "POST",
                headers = mapOf(
                    "Content-Type" to JSON_MEDIA_TYPE.toString(),
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to baseUrl.resolve("/manage/classroom/attendance").toString(),
                ),
                // The String overload adds a charset parameter, so use bytes to keep the LMS wire
                // header exactly application/json.
                body = payload.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA_TYPE),
            )
        } catch (error: LmsHttpException) {
            if (error.status != 409) throw error
            // A conflict can mean that the LMS accepted this or another near-simultaneous mark.
            // Never repeat the POST blindly: confirm against the authoritative classroom page.
            val detail = classroom(nid)
            if (detail.marked) return detail
            throw error
        }

        // The POST response body is not authoritative. The LMS sometimes returns success even
        // when the attendance window closed between the list read and this request.
        return classroom(nid)
    }

    override fun resetSession() {
        uid = null
        displayName = null
        activeCredentials = null
        sessionRevision.incrementAndGet()
        invalidateContentCache()
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
        if (loginPage.status >= 400) throw LmsHttpException(loginPage.status, loginPage.url)
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

        if (loginResult.status >= 400) throw LmsHttpException(loginResult.status, loginResult.url)
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
        sessionRevision.incrementAndGet()
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
        val observedRevision = sessionRevision.get()
        var response = requestRaw(path, method, headers, body)
        if (!looksLoggedOut(response)) {
            if (response.status >= 400) throw LmsHttpException(response.status, response.url)
            return response
        }

        val credentials = activeCredentials ?: defaultCredentials()
        loginMutex.withLock {
            // Only the first expired response replaces the session. Late responses
            // from the old cookie must not clear the freshly authenticated cookie.
            if (sessionRevision.get() == observedRevision) {
                activeCredentials = credentials
                doLogin(credentials)
            }
        }
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
            beforeRequest()
            val builder = Request.Builder().url(url)
            currentHeaders.forEach { (key, value) -> builder.header(key, value) }
            if (currentMethod == "GET" || currentMethod == "HEAD") {
                builder.method(currentMethod, null)
            } else {
                builder.method(currentMethod, currentBody ?: EMPTY_BODY)
            }

            val response = try {
                client.newCall(builder.build()).awaitBody()
            } catch (error: IOException) {
                lmsDiagnosticReporter.record(lmsEndpointLabel(url.encodedPath), null, "network")
                throw LmsException("LMS network request failed for ${lmsEndpointLabel(url.encodedPath)}.", error)
            }
            if (response.status >= 400) {
                lmsDiagnosticReporter.record(lmsEndpointLabel(url.encodedPath), response.status, "http")
            }
            val location = response.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value
            if (response.status in REDIRECT_STATUSES && location != null) {
                val next = response.url.toHttpUrl().resolve(location)
                    ?: throw LmsProtocolException("LMS returned an invalid redirect.")
                requireSameOrigin(next)
                url = next
                if (currentMethod == "POST" && response.status !in PRESERVE_METHOD_REDIRECTS) {
                    currentMethod = "GET"
                    currentBody = null
                    currentHeaders = currentHeaders
                        .filterKeys { !it.equals("Content-Type", ignoreCase = true) }
                        .toMutableMap()
                }
                return@repeat
            }
            return response
        }

        throw LmsProtocolException("Too many redirects starting at $path.")
    }

    /** The callback consumes and closes the body on OkHttp's thread, never the UI thread. */
    private suspend fun Call.awaitBody(): ResponseData = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        ResponseData(
                            it.code,
                            it.request.url.toString(),
                            it.headers.toMultimap().mapValues { (_, values) -> values.joinToString(",") },
                            it.body?.string().orEmpty(),
                        )
                    }
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun requireSameOrigin(url: HttpUrl) {
        if (url.scheme != baseUrl.scheme || url.host != baseUrl.host || url.port != baseUrl.port) {
            throw LmsProtocolException("LMS request left the trusted origin.")
        }
    }

    private fun urlFor(path: String): HttpUrl = (baseUrl.resolve(path)
        ?: throw LmsProtocolException("Invalid LMS URL")).also(::requireSameOrigin)

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

private val ORIENTATION_START = LocalDate.of(2026, 7, 27)
    .atStartOfDay(ZoneId.of("Asia/Kolkata"))
    .toEpochSecond()
private val ORIENTATION_END_EXCLUSIVE = LocalDate.of(2026, 8, 8)
    .atStartOfDay(ZoneId.of("Asia/Kolkata"))
    .toEpochSecond()

internal fun attendanceReportWindows(historyStart: Long, historyEnd: Long): List<LongRange> = buildList {
    val beforeOrientationEnd = minOf(historyEnd, ORIENTATION_START - 1L)
    if (historyStart <= beforeOrientationEnd) add(historyStart..beforeOrientationEnd)

    val afterOrientationStart = maxOf(historyStart, ORIENTATION_END_EXCLUSIVE)
    if (afterOrientationStart <= historyEnd) add(afterOrientationStart..historyEnd)
}

internal fun combineAttendanceSummaries(summaries: List<AttendanceSummary>): AttendanceSummary {
    val present = summaries.sumOf(AttendanceSummary::present)
    val absent = summaries.sumOf(AttendanceSummary::absent)
    val notMarked = summaries.sumOf(AttendanceSummary::notMarked)
    return attendanceSummary(present, absent, notMarked)
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

    // Some learner reports return an inconsistent aggregate Total. Require the expected row,
    // but derive the displayed total from the mutually exclusive classifications below.
    count("total")
    val present = count("present")
    val absent = count("absent")
    val notMarked = count("not marked")
    return attendanceSummary(present, absent, notMarked)
}

private fun attendanceSummary(present: Int, absent: Int, notMarked: Int): AttendanceSummary {
    val total = present + absent + notMarked
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
