package org.isdm.companion.data

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

class RealLmsAdapterTest {
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
          <tr><td class="col-1">Attendance Marked (For me)</td><td class="mid">:</td><td class="col-2">${if (marked) "Yes" else "No"}</td></tr>
          <tr><td class="col-1">Status</td><td class="mid">:</td><td class="col-2">${if (marked) "Present" else "Not Marked"}</td></tr>
        </tbody></table>
    """.trimIndent()
}
