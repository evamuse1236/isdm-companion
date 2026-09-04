package org.isdm.companion.data

import java.time.Instant
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.isdm.companion.engine.Credentials

class RealLmsAdapterTest {
    @Test
    fun `attendance summary excludes orientation sessions from 27 July through 7 August`() = runBlocking {
        val server = MockWebServer()
        val reportWindows = mutableListOf<Pair<Long, Long>>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path == "/user/login" -> MockResponse()
                    .setHeader("Set-Cookie", "SESSattendance=trusted; Path=/; HttpOnly")
                    .setBody(loginForm())
                request.method == "POST" && request.path == "/user/login" -> MockResponse()
                    .setResponseCode(302).setHeader("Location", "/home")
                request.method == "GET" && request.path == "/home" -> MockResponse()
                    .setBody("<a href='/user/1042/edit/chgpwd'>x</a>")
                request.method == "GET" &&
                    request.requestUrl?.encodedPath == "/api/executereport" &&
                    request.requestUrl?.queryParameter("report") ==
                    "student-dashboard-user-classroom-session-summary" -> {
                    reportWindows += request.requestUrl!!.queryParameter("start_time")!!.toLong() to
                        request.requestUrl!!.queryParameter("end_time")!!.toLong()
                    val beforeOrientation = reportWindows.size == 1
                    MockResponse().setBody(
                        if (beforeOrientation) {
                            attendanceReport(total = 20, present = 15, absent = 5, notMarked = 0)
                        } else {
                            attendanceReport(total = 10, present = 7, absent = 2, notMarked = 1)
                        },
                    )
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        try {
            val now = Instant.parse("2026-08-23T06:30:00Z")
            val summary = RealLmsAdapter(
                "student@example.com",
                "secret",
                server.url("/").toString(),
                now = { now },
            )
                .attendanceSummary()

            assertEquals(30, summary.total)
            assertEquals(22, summary.present)
            assertEquals(7, summary.absent)
            assertEquals(1, summary.notMarked)
            assertEquals("73.33", summary.presentPercentage.toPlainString())
            assertEquals(
                listOf(
                    (now.epochSecond - 365L * 24L * 60L * 60L) to
                        (Instant.parse("2026-07-26T18:30:00Z").epochSecond - 1L),
                    Instant.parse("2026-08-07T18:30:00Z").epochSecond to now.epochSecond,
                ),
                reportWindows,
            )
        } finally {
            server.shutdown()
        }
    }

    private fun attendanceReport(
        total: Int,
        present: Int,
        absent: Int,
        notMarked: Int,
    ): String = """
        [
          { "count": $total, "title": "Total" },
          { "count": $notMarked, "title": "Not Marked" },
          { "count": $present, "title": "Present" },
          { "count": $absent, "title": "Absent" }
        ]
    """.trimIndent()

    @Test
    fun `attendance summary remains available when the LMS reported total is inconsistent`() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path == "/user/login" -> MockResponse()
                    .setHeader("Set-Cookie", "SESSattendance=trusted; Path=/; HttpOnly")
                    .setBody(loginForm())
                request.method == "POST" && request.path == "/user/login" -> MockResponse()
                    .setResponseCode(302).setHeader("Location", "/home")
                request.method == "GET" && request.path == "/home" -> MockResponse()
                    .setBody("<a href='/user/1042/edit/chgpwd'>x</a>")
                request.method == "GET" &&
                    request.requestUrl?.encodedPath == "/api/executereport" -> MockResponse().setBody(
                    """
                    [
                      { "count": 54, "title": "Total" },
                      { "count": 3, "title": "Not Marked" },
                      { "count": 40, "title": "Present" },
                      { "count": 14, "title": "Absent" }
                    ]
                    """.trimIndent(),
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        try {
            val summary = RealLmsAdapter(
                "student@example.com",
                "secret",
                server.url("/").toString(),
                now = { Instant.parse("2026-07-01T06:30:00Z") },
            )
                .attendanceSummary()

            assertEquals(57, summary.total)
            assertEquals(40, summary.present)
            assertEquals(14, summary.absent)
            assertEquals(3, summary.notMarked)
            assertEquals("70.18", summary.presentPercentage.toPlainString())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `LMS diagnostics reduce request paths to safe endpoint labels`() {
        assertEquals("calendar", lmsEndpointLabel("/calendar/json"))
        assertEquals("course_details", lmsEndpointLabel("/course/details"))
        assertEquals("classroom", lmsEndpointLabel("/classroom/1285348/view"))
        assertEquals("attendance_summary", lmsEndpointLabel("/api/executereport"))
        assertEquals("other", lmsEndpointLabel("/unexpected/private/path"))
    }

    @Test
    fun `HTTP failures report only endpoint status and failure type`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503).setBody("private LMS response"))
        server.start()
        val diagnostics = mutableListOf<Triple<String, Int?, String>>()
        val adapter = RealLmsAdapter(
            baseUrl = server.url("/").toString(),
            lmsDiagnosticReporter = LmsDiagnosticReporter { endpoint, status, failureType ->
                diagnostics += Triple(endpoint, status, failureType)
            },
        )
        try {
            runCatching { adapter.login(Credentials("student@example.com", "secret")) }

            assertEquals(listOf(Triple("login", 503, "http")), diagnostics)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun classroomDetailProvidesTheLmsEndTime() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path == "/user/login" -> MockResponse()
                    .setHeader("Set-Cookie", "SESSdetail=trusted; Path=/; HttpOnly")
                    .setBody(loginForm())
                request.method == "POST" && request.path == "/user/login" -> MockResponse()
                    .setResponseCode(302).setHeader("Location", "/home")
                request.method == "GET" && request.path == "/home" -> MockResponse()
                    .setBody("<a href='/user/1042/edit/chgpwd'>x</a>")
                request.method == "GET" && request.path == "/classroom/1285348/view" -> MockResponse()
                    .setBody(classroom(marked = false))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        try {
            val detail = RealLmsAdapter("student@example.com", "secret", server.url("/").toString())
                .classroom("1285348")

            assertEquals(Instant.parse("2026-08-16T06:30:00Z"), detail.end)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun readingDownloadResolvesTheSignedNativePdfUrl() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path == "/user/login" -> MockResponse()
                    .setHeader("Set-Cookie", "SESSdownload=trusted; Path=/; HttpOnly")
                    .setBody(loginForm())
                request.method == "POST" && request.path == "/user/login" -> MockResponse()
                    .setResponseCode(302).setHeader("Location", "/home")
                request.method == "GET" && request.path == "/home" -> MockResponse()
                    .setBody("<a href='/user/1042/edit/chgpwd'>x</a>")
                request.path?.startsWith("/api/downloadcontent?") == true -> MockResponse().setBody(
                    """[{"videourl":{"native":"https://files.example/reading.pdf?signature=ok"}}]""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        try {
            val adapter = RealLmsAdapter(baseUrl = server.url("/").toString())
            val url = adapter.readingDownloadUrl(
                Credentials("student@example.com", "secret"),
                server.url("/subtopic/view?sid=1296141&vid=1298349").toString(),
            )

            assertEquals("https://files.example/reading.pdf?signature=ok", url)
            val requests = generateSequence { server.takeRequest() }.take(4).toList()
            val download = requests.last()
            assertEquals("1298349", download.requestUrl?.queryParameter("nid"))
            assertEquals("1296141", download.requestUrl?.queryParameter("courseid"))
            assertEquals("1042", download.requestUrl?.queryParameter("uid"))
            assertEquals("native", download.requestUrl?.queryParameter("format"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun browserCookiesReuseTheValidatedLmsSession() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path == "/user/login" -> MockResponse()
                    .setHeader("Set-Cookie", "SESSbrowser=trusted; Path=/; HttpOnly")
                    .setBody(loginForm())
                request.method == "POST" && request.path == "/user/login" -> MockResponse()
                    .setResponseCode(302).setHeader("Location", "/home")
                request.method == "GET" && request.path == "/home" -> MockResponse()
                    .setBody("<a href='/user/1042/edit/chgpwd'>x</a>")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        try {
            val adapter = RealLmsAdapter(baseUrl = server.url("/").toString())
            val cookies = adapter.browserCookieHeaders(Credentials("student@example.com", "secret"))

            assertTrue(cookies.any { it.startsWith("SESSbrowser=trusted") })
            assertTrue(cookies.any { "httponly" in it.lowercase() })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun readingsAreAggregatedFromListingPagesWithoutOpeningItems() = runBlocking {
        val server = MockWebServer()
        val paths = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path.orEmpty()
                return when {
                    request.method == "GET" && request.path == "/user/login" -> MockResponse()
                        .setHeader("Set-Cookie", "SESSreadings=one; Path=/")
                        .setBody(loginForm())
                    request.method == "POST" && request.path == "/user/login" -> MockResponse()
                        .setResponseCode(302).setHeader("Location", "/home")
                    request.method == "GET" && request.path == "/home" -> MockResponse()
                        .setBody("<a href='/user/1042/edit/chgpwd'>x</a>")
                    request.path == "/show/all/courses" -> MockResponse().setBody(
                        "<a href='/course/details?cat_id=12'>State, Market and Society</a>",
                    )
                    request.path == "/course/details?cat_id=12" -> MockResponse().setBody(
                        """
                        <a href='/course/details?cat_id=12&amp;course_id=91'>Mandatory Reading</a>
                        <a href='/course/details?cat_id=12&amp;course_id=95' title='Course Outline'>Open</a>
                        """.trimIndent(),
                    )
                    request.path == "/course/details?cat_id=12&course_id=91" -> MockResponse().setBody(
                        """
                        <div class='document_status_icon'>
                          <a href='/subtopic/view?sid=91&amp;vid=501&amp;cid=7&amp;cat_id=12'>Seeing Like a State</a>
                        </div>
                        """.trimIndent(),
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        try {
            val adapter = RealLmsAdapter("a@b.c", "password", server.url("/").toString())
            val course = adapter.courses().single()
            val reading = adapter.readings(course).single()

            assertEquals("12", course.catId)
            assertEquals("501", reading.vid)
            assertTrue(reading.mandatory)
            assertEquals("Course Outline", reading.courseOutlineTitle)
            assertEquals(
                server.url("/course/details?cat_id=12&course_id=95").toString(),
                reading.courseOutlineUrl,
            )
            assertFalse(paths.any { it.startsWith("/subtopic/view") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun assessmentsUseTheTaskListDueDateWhenTheLiveSubmissionHasNoFrame() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path == "/user/login" -> MockResponse()
                    .setHeader("Set-Cookie", "SESSassessments=one; Path=/")
                    .setBody(loginForm())
                request.method == "POST" && request.path == "/user/login" -> MockResponse()
                    .setResponseCode(302).setHeader("Location", "/home")
                request.method == "GET" && request.path == "/home" -> MockResponse()
                    .setBody("<a href='/user/1042/edit/chgpwd'>x</a>")
                request.path == "/my-activities" -> MockResponse().setBody(
                    """
                    <table><tr>
                      <td><p>B10 - T1 - PMDL - Reflection 2 from topic Assessments</p>
                        <span>Starts On: 17-Aug-2026 - 11:37 AM</span>
                        <span>Due On: 20-Sep-2026 - 12:32 PM</span>
                        <span>Status: Not Submitted</span>
                      </td>
                      <td><a href='/subtopic/view?sid=1298915&amp;vid=1305879&amp;cid=1298950&amp;cat_id=70954&amp;destination=my-activities'>Take Activity</a></td>
                    </tr></table>
                    """.trimIndent(),
                )
                request.path?.startsWith("/subtopic/view?sid=1298915&vid=1305879") == true -> MockResponse().setBody(
                    "<main>Assessment submission form without an iframe or date labels</main>",
                )
                request.path == "/course/details?cat_id=70954&course_id=1298915" -> MockResponse().setBody(
                    """
                    <a href='/subtopic/view?sid=1298915&amp;vid=1305879&amp;cid=1298950&amp;cat_id=70954'>Reflection Prompt 1 - Submission Link</a>
                    <a href='/subtopic/view?sid=1298915&amp;vid=1306062&amp;cid=1298950&amp;cat_id=70954'>Reflection Prompt 2 - Instructions</a>
                    <a href='/download/video?sid=1298915&amp;vid=1306062&amp;cid=1298950&amp;cat_id=70954'></a>
                    """.trimIndent(),
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        try {
            val adapter = RealLmsAdapter("a@b.c", "password", server.url("/").toString())
            val assessment = adapter.assessments().single()

            assertEquals("B10 - T1 - PMDL - Reflection 2", assessment.title)
            assertEquals(java.time.LocalDate.of(2026, 9, 20), assessment.dueDate)
            assertEquals(null, assessment.endDate)
            assertEquals("Not Submitted", assessment.status)
            assertEquals("Reflection Prompt 2 - Instructions", assessment.resourceTitle)
            assertTrue(assessment.resourceUrl?.contains("vid=1306062") == true)
            assertTrue(assessment.submissionUrl.contains("vid=1305879"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `assessment refresh retains task drafts when optional resource access is denied`() = runBlocking {
        val server = MockWebServer()
        var failedSectionFetches = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path == "/user/login" -> MockResponse()
                    .setHeader("Set-Cookie", "SESSassessmentPartial=one; Path=/")
                    .setBody(loginForm())
                request.method == "POST" && request.path == "/user/login" -> MockResponse()
                    .setResponseCode(302).setHeader("Location", "/home")
                request.method == "GET" && request.path == "/home" -> MockResponse()
                    .setBody("<a href='/user/1042/edit/chgpwd'>x</a>")
                request.path == "/my-activities" -> MockResponse().setBody(
                    """
                    <table>
                      <tr><td><p>B10 - T1 - PMDL - Reflection 1 from topic Assessments</p><span>Starts On: 17-Aug-2026</span><span>Due On: 20-Sep-2026</span><span>Status: Not Submitted</span></td><td><a href='/subtopic/view?sid=101&amp;vid=1301&amp;cid=11&amp;cat_id=1&amp;destination=my-activities'>Take Activity</a></td></tr>
                      <tr><td><p>B10 - T1 - PMDL - Reflection 2 from topic Assessments</p><span>Starts On: 17-Aug-2026</span><span>Due On: 21-Sep-2026</span><span>Status: Not Submitted</span></td><td><a href='/subtopic/view?sid=102&amp;vid=1302&amp;cid=12&amp;cat_id=2&amp;destination=my-activities'>Take Activity</a></td></tr>
                      <tr><td><p>B10 - T1 - PMDL - Reflection 3 from topic Assessments</p><span>Starts On: 17-Aug-2026</span><span>Due On: 22-Sep-2026</span><span>Status: Not Submitted</span></td><td><a href='/subtopic/view?sid=103&amp;vid=1303&amp;cid=13&amp;cat_id=3&amp;destination=my-activities'>Take Activity</a></td></tr>
                      <tr><td><p>B10 - T1 - PMDL - Reflection 4 from topic Assessments</p><span>Starts On: 17-Aug-2026</span><span>Due On: 23-Sep-2026</span><span>Status: Not Submitted</span></td><td><a href='/subtopic/view?sid=103&amp;vid=1304&amp;cid=14&amp;cat_id=3&amp;destination=my-activities'>Take Activity</a></td></tr>
                    </table>
                    """.trimIndent(),
                )
                request.path?.startsWith("/subtopic/view?sid=101&vid=1301") == true -> MockResponse().setBody(
                    "<iframe id='iframe_load' src='/activity/user/attempt?nid=1201&amp;videoid=1301'></iframe>",
                )
                request.path == "/activity/user/attempt?nid=1201&videoid=1301" -> MockResponse().setBody(
                    "<div>Due Date : 19/09/2026</div><div>End Date : 20/09/2026</div>",
                )
                request.path == "/course/details?cat_id=1&course_id=101" -> MockResponse().setBody(
                    """
                    <a href='/subtopic/view?sid=101&amp;vid=1301&amp;cid=11&amp;cat_id=1'>Reflection Prompt 1</a>
                    <a href='/download/video?sid=101&amp;vid=1301&amp;cid=11&amp;cat_id=1'></a>
                    """.trimIndent(),
                )
                request.path?.startsWith("/subtopic/view?sid=102&vid=1302") == true -> MockResponse()
                    .setResponseCode(403)
                request.path?.startsWith("/subtopic/view?sid=103&vid=1303") == true -> MockResponse().setBody(
                    "<iframe id='iframe_load' src='/activity/user/attempt?nid=1203&amp;videoid=1303'></iframe>",
                )
                request.path?.startsWith("/subtopic/view?sid=103&vid=1304") == true -> MockResponse()
                    .setResponseCode(503)
                request.path == "/activity/user/attempt?nid=1203&videoid=1303" -> MockResponse().setBody(
                    "<div>Due Date : 18/09/2026</div><div>End Date : 22/09/2026</div>",
                )
                request.path == "/course/details?cat_id=3&course_id=103" -> {
                    failedSectionFetches += 1
                    MockResponse().setResponseCode(503)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        try {
            val assessments = RealLmsAdapter("a@b.c", "password", server.url("/").toString())
                .assessments()

            assertEquals(4, assessments.size)
            assertEquals(java.time.LocalDate.of(2026, 9, 19), assessments[0].dueDate)
            assertTrue(assessments[0].submissionUrl.contains("/activity/user/attempt"))
            assertEquals("Reflection Prompt 1", assessments[0].resourceTitle)
            assertEquals(java.time.LocalDate.of(2026, 9, 21), assessments[1].dueDate)
            assertTrue(assessments[1].submissionUrl.contains("vid=1302"))
            assertEquals(null, assessments[1].resourceUrl)
            assertEquals(java.time.LocalDate.of(2026, 9, 18), assessments[2].dueDate)
            assertTrue(assessments[2].submissionUrl.contains("/activity/user/attempt"))
            assertEquals(null, assessments[2].resourceUrl)
            assertEquals(java.time.LocalDate.of(2026, 9, 23), assessments[3].dueDate)
            assertEquals(1, failedSectionFetches)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `assessment refresh propagates an unauthenticated optional request`() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path == "/user/login" -> MockResponse()
                    .setHeader("Set-Cookie", "SESSassessmentUnauthorized=one; Path=/")
                    .setBody(loginForm())
                request.method == "POST" && request.path == "/user/login" -> MockResponse()
                    .setResponseCode(302).setHeader("Location", "/home")
                request.method == "GET" && request.path == "/home" -> MockResponse()
                    .setBody("<a href='/user/1042/edit/chgpwd'>x</a>")
                request.path == "/my-activities" -> MockResponse().setBody(
                    """
                    <table><tr><td><p>B10 - T1 - PMDL - Reflection 1 from topic Assessments</p><span>Starts On: 17-Aug-2026</span><span>Due On: 20-Sep-2026</span><span>Status: Not Submitted</span></td><td><a href='/subtopic/view?sid=101&amp;vid=1301&amp;cid=11&amp;cat_id=1&amp;destination=my-activities'>Take Activity</a></td></tr></table>
                    """.trimIndent(),
                )
                request.path?.startsWith("/subtopic/view?sid=101&vid=1301") == true -> MockResponse()
                    .setResponseCode(401)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        try {
            val error = runCatching {
                RealLmsAdapter("a@b.c", "password", server.url("/").toString()).assessments()
            }.exceptionOrNull()

            assertTrue(error is LmsHttpException)
            assertEquals(401, (error as LmsHttpException).status)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun assessmentSubmitOpensTheEmbeddedLmsForm() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path == "/user/login" -> MockResponse()
                    .setHeader("Set-Cookie", "SESSassessmentform=one; Path=/")
                    .setBody(loginForm())
                request.method == "POST" && request.path == "/user/login" -> MockResponse()
                    .setResponseCode(302).setHeader("Location", "/home")
                request.method == "GET" && request.path == "/home" -> MockResponse()
                    .setBody("<a href='/user/1042/edit/chgpwd'>x</a>")
                request.path == "/my-activities" -> MockResponse().setBody(
                    """
                    <table><tr>
                      <td><p>B10 - T1 - PMDL - Reflection 2 from topic Assessments</p>
                        <span>Starts On: 17-Aug-2026 - 11:37 AM</span>
                        <span>Due On: 20-Sep-2026 - 12:32 PM</span>
                        <span>Status: Not Submitted</span>
                      </td>
                      <td><a href='/subtopic/view?sid=1298915&amp;vid=1305879&amp;cid=1298950&amp;cat_id=70954&amp;destination=my-activities'>Take Activity</a></td>
                    </tr></table>
                    """.trimIndent(),
                )
                request.path?.startsWith("/subtopic/view?sid=1298915&vid=1305879") == true -> MockResponse().setBody(
                    "<iframe id='iframe_load' src='/activity/user/attempt?nid=1305877&amp;videoid=1305879'></iframe>",
                )
                request.path == "/activity/user/attempt?nid=1305877&videoid=1305879" -> MockResponse().setBody(
                    "<div>Due Date : 20/08/2026</div><div>End Date : 20/09/2026</div>",
                )
                request.path == "/course/details?cat_id=70954&course_id=1298915" -> MockResponse().setBody(
                    """
                    <a href='/subtopic/view?sid=1298915&amp;vid=1306062&amp;cid=1298950&amp;cat_id=70954'>Reflection Prompt 2</a>
                    <a href='/download/video?sid=1298915&amp;vid=1306062&amp;cid=1298950&amp;cat_id=70954'></a>
                    """.trimIndent(),
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        try {
            val assessment = RealLmsAdapter("a@b.c", "password", server.url("/").toString())
                .assessments().single()

            assertEquals(java.time.LocalDate.of(2026, 8, 20), assessment.dueDate)
            assertEquals(java.time.LocalDate.of(2026, 9, 20), assessment.endDate)
            assertTrue(assessment.submissionUrl.contains("/activity/user/attempt"))
            assertTrue(assessment.submissionUrl.contains("nid=1305877"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun loginCookieIsCarriedAndMarkIsConfirmedByReread() = runBlocking {
        val server = MockWebServer()
        val requests = mutableListOf<RequestRecord>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                requests += RequestRecord(
                    request.method.orEmpty(),
                    request.path.orEmpty(),
                    request.headers.toMultimap().mapKeys { it.key.lowercase() },
                    body,
                )
                return when {
                    request.method == "GET" && request.path?.startsWith("/user/login") == true -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/html")
                        .setHeader("Set-Cookie", "SESSabc=cookievalue; Path=/; HttpOnly")
                        .setBody(loginForm())

                    request.method == "POST" && request.path == "/user/login" -> MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", "/home")

                    request.method == "GET" && request.path == "/home" -> MockResponse()
                        .setBody("<a href=\"/user/1042/edit/chgpwd\">Change Password</a>")

                    request.method == "POST" && request.path == "/api/mark/classroomsession/attendance" -> MockResponse()
                        .setBody("success")

                    request.method == "GET" && request.path == "/classroom/1285348/view" -> MockResponse()
                        .setBody(classroom(marked = true))

                    else -> MockResponse().setResponseCode(404).setBody("not found")
                }
            }
        }
        server.start()

        try {
            val adapter = RealLmsAdapter(
                defaultEmail = "student@pgp.isdm.org.in",
                defaultPassword = "hunter2",
                baseUrl = server.url("/").toString(),
            )

            val detail = adapter.markPresent("1285348")

            assertTrue(detail.marked)
            assertEquals("Majlis", detail.room)

            val loginPost = requests.first { it.method == "POST" && it.path == "/user/login" }
            val loginBody = parseForm(loginPost.body)
            assertEquals("student@pgp.isdm.org.in", loginBody["name"])
            assertEquals("hunter2", loginBody["pass"])
            assertEquals("user_login", loginBody["form_id"])
            assertEquals("form-xyz", loginBody["form_build_id"])
            assertEquals("form-xyz", loginBody["st"])

            val mark = requests.first {
                it.method == "POST" && it.path == "/api/mark/classroomsession/attendance"
            }
            assertEquals("application/json", mark.headers["content-type"]?.single())
            assertEquals("XMLHttpRequest", mark.headers["x-requested-with"]?.single())
            assertTrue(mark.headers["cookie"].orEmpty().any { it.contains("SESSabc=cookievalue") })
            val markJson = JSONObject(mark.body)
            assertEquals("1285348", markJson.getString("nid"))
            assertEquals("1042", markJson.getString("uid"))
            assertEquals("present", markJson.getString("status"))

            val confirmation = requests.filter { it.path == "/classroom/1285348/view" }
            assertEquals(1, confirmation.size)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun attendanceConflictIsConfirmedByAuthoritativeReread() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path == "/user/login" -> MockResponse()
                    .setHeader("Set-Cookie", "SESSconflict=one; Path=/")
                    .setBody(loginForm())
                request.method == "POST" && request.path == "/user/login" -> MockResponse()
                    .setResponseCode(302).setHeader("Location", "/home")
                request.method == "GET" && request.path == "/home" -> MockResponse()
                    .setBody("<a href='/user/1042/edit/chgpwd'>x</a>")
                request.method == "POST" && request.path == "/api/mark/classroomsession/attendance" ->
                    MockResponse().setResponseCode(409).setBody("conflict")
                request.method == "GET" && request.path == "/classroom/1285348/view" -> MockResponse()
                    .setBody(classroom(marked = true))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        try {
            val detail = RealLmsAdapter("a@b.c", "password", server.url("/").toString())
                .markPresent("1285348")

            assertTrue(detail.marked)
            assertEquals("Majlis", detail.room)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun calendarAndMarkabilityParseTheLmsShapes() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path == "/user/login" -> MockResponse()
                    .setHeader("Set-Cookie", "SESScalendar=one; Path=/")
                    .setBody(loginForm())

                request.method == "POST" && request.path == "/user/login" -> MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "/home")

                request.method == "GET" && request.path == "/home" -> MockResponse()
                    .setBody("<a href=\"/user/1042/edit/chgpwd\">x</a>")

                request.method == "GET" && request.path?.startsWith("/calendar/json") == true -> MockResponse()
                    .setBody(
                        """
                        [
                          {"nid":"900","title":"Excel - Section B - Session 4","url":"/join/webinar?nid=900","start":"2026-08-08 11:30:00","end":"2026-08-08 13:00:00","className":"event-active","batch":"PGP-DM 2026-27"},
                          {"nid":"901","title":"Attendance - Excel - Section B - Session 4","url":"/classroom/901/view","start":"2026-08-08 11:30:00","end":"2026-08-08 13:00:00","className":"event-active","trainers":"Trainer","subject":"B10 - T0 - Excel"}
                        ]
                        """.trimIndent(),
                    )

                request.method == "GET" && request.path == "/manage/classroom/attendance" -> MockResponse()
                    .setBody(
                        """
                        <div title="Mark Attendance" class="mark-attend-disabled" id="900" uid="1042"></div>
                        <div title="Mark Attendance" class="mark-attend" id="901" uid="1042"></div>
                        """.trimIndent(),
                    )

                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        try {
            val adapter = RealLmsAdapter("a@b.c", "password", server.url("/").toString())
            val events = adapter.calendar("2026-08-08", "2026-08-09")
            val markability = adapter.markability()

            assertEquals(2, events.size)
            assertEquals("Attendance - Excel - Section B - Session 4", events[1].title)
            assertEquals("Trainer", events[1].trainers)
            assertFalse(markability.getValue("900").markable)
            assertTrue(markability.getValue("901").markable)
            assertEquals("1042", markability.getValue("901").uid)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun aLoggedOutReadGetsOneFreshLoginThenSucceeds() = runBlocking {
        val server = MockWebServer()
        var loginPosts = 0
        var calendarReads = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path == "/user/login" -> MockResponse()
                    .setHeader("Set-Cookie", "SESS$loginPosts=one; Path=/")
                    .setBody(loginForm())

                request.method == "POST" && request.path == "/user/login" -> {
                    loginPosts += 1
                    MockResponse().setResponseCode(302).setHeader("Location", "/home")
                }

                request.method == "GET" && request.path == "/home" -> MockResponse()
                    .setBody("<a href=\"/user/1042/edit/chgpwd\">x</a>")

                request.method == "GET" && request.path?.startsWith("/calendar/json") == true -> {
                    calendarReads += 1
                    if (calendarReads == 1) MockResponse().setBody(loginForm())
                    else MockResponse().setBody("[]")
                }

                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        try {
            val adapter = RealLmsAdapter("a@b.c", "password", server.url("/").toString())
            assertTrue(adapter.calendar("2026-08-08", "2026-08-09").isEmpty())
            assertEquals(2, loginPosts)
            assertEquals(2, calendarReads)
        } finally {
            server.shutdown()
        }
    }

    private data class RequestRecord(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String,
    )

    private fun parseForm(body: String): Map<String, String> =
        body.split('&')
            .filter { it.isNotBlank() }
            .associate { pair ->
                val parts = pair.split('=', limit = 2)
                java.net.URLDecoder.decode(parts[0], Charsets.UTF_8.name()) to
                    java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8.name())
            }

    private fun loginForm(): String = """
        <form action="/user/login" method="post" id="user-login">
          <input type="text" name="name">
          <input type="password" name="pass">
          <input type="submit" name="op" value="Sign in">
          <input type="hidden" name="form_build_id" value="form-xyz">
          <input type="hidden" name="form_id" value="user_login">
          <input type="hidden" name="st" value="">
        </form>
    """.trimIndent()

    private fun classroom(marked: Boolean): String = """
        <table><tbody>
          <tr><td class="col-1">Title</td><td class="mid">:</td><td class="col-2">Attendance - Bricolage</td></tr>
          <tr><td class="col-1">Location</td><td class="mid">:</td><td class="col-2">Majlis</td></tr>
          <tr><td class="col-1">End Date &amp; Time</td><td class="mid">:</td><td class="col-2">2026-08-16 12:00:00</td></tr>
          <tr><td class="col-1">Attendance Marked (For me)</td><td class="mid">:</td><td class="col-2">${if (marked) "Yes" else "No"}</td></tr>
          <tr><td class="col-1">Status</td><td class="mid">:</td><td class="col-2">${if (marked) "Present" else "Not Marked"}</td></tr>
        </tbody></table>
    """.trimIndent()
}
