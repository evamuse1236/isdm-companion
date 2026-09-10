package org.isdm.companion.data

import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.isdm.companion.engine.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LmsSessionRecoveryTest {
    private val loginForm = """<form action='/user/login'><input name='name' type='text'>
        <input name='pass' type='password'><input name='form_id' value='user_login'></form>"""
    private val identity = "<a href='/user/1042/edit/chgpwd'>Profile</a>"
    private val today = LocalDate.of(2026, 9, 11)

    @Test
    fun `readings and faculty share one course page and refresh invalidates it`() = runBlocking {
        MockWebServer().use { server ->
            val pageCount = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "POST" -> MockResponse().setResponseCode(302).setHeader("Location", "/home")
                    request.path == "/user/login" -> MockResponse().setBody(loginForm)
                    request.path == "/home" -> MockResponse().setBody(identity)
                    request.requestUrl?.encodedPath == "/course/details" -> {
                        pageCount.incrementAndGet()
                        MockResponse().setBody("<main>No content in this fixture</main>").setBodyDelay(50, TimeUnit.MILLISECONDS)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val adapter = RealLmsAdapter("learner@example.test", "test-only", server.url("/").toString())
            val course = org.isdm.companion.engine.LmsCourse("42", "Methods")
            val readings = async { adapter.readings(course) }
            val profiles = async { adapter.facultyProfiles(course) }
            readings.await()
            profiles.await()
            assertEquals(1, pageCount.get())
            adapter.invalidateContentCache()
            adapter.facultyProfiles(course)
            assertEquals(2, pageCount.get())
        }
    }

    @Test
    fun `slow response body does not block caller event loop`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(loginForm))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/home"))
            server.enqueue(MockResponse().setBody(identity))
            server.enqueue(MockResponse().setBody("[]").setBodyDelay(700, TimeUnit.MILLISECONDS))
            server.start()
            val adapter = RealLmsAdapter(baseUrl = server.url("/").toString())
            adapter.login(Credentials("learner@example.test", "test-only"))
            val started = System.nanoTime()
            var heartbeatMs = Long.MAX_VALUE
            val heartbeat = launch {
                delay(100)
                heartbeatMs = (System.nanoTime() - started) / 1_000_000
            }
            adapter.calendar(today, today.plusDays(1))
            heartbeat.join()
            println("700ms HTTP body fixture: caller heartbeat after ${heartbeatMs}ms")
            assertTrue("UI heartbeat waited ${heartbeatMs}ms for an HTTP body", heartbeatMs < 500)
        }
    }

    @Test
    fun `simultaneous expired requests share one saved credential login`() = runBlocking {
        MockWebServer().use { server ->
            val expiredReaders = CountDownLatch(3)
            val calendarCount = AtomicInteger()
            val loginCount = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "POST" && request.path == "/user/login" -> {
                        loginCount.incrementAndGet()
                        MockResponse().setResponseCode(302).setHeader("Location", "/home")
                    }
                    request.path == "/home" -> MockResponse().setBody(identity)
                    request.path == "/user/login" -> MockResponse().setBody(loginForm)
                    request.requestUrl?.encodedPath == "/calendar/json" -> {
                        if (calendarCount.incrementAndGet() <= 3) {
                            expiredReaders.countDown()
                            check(expiredReaders.await(3, TimeUnit.SECONDS))
                            MockResponse().setBody(loginForm)
                        } else MockResponse().setBody("[]")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val adapter = RealLmsAdapter(baseUrl = server.url("/").toString())
            adapter.login(Credentials("learner@example.test", "test-only"))
            val results = (1..3).map { async { adapter.calendar(today, today.plusDays(1)) } }.awaitAll()
            assertTrue(results.all { it.isEmpty() })
            assertEquals("One initial login and one shared recovery", 2, loginCount.get())
        }
    }
}
