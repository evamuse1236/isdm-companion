package org.isdm.companion.platform

import java.time.Instant
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BetaApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: BetaApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = BetaApiClient(server.url("/").toString().trimEnd('/'), OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `tester enrolls with profile and receives installation credentials`() {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"tester_code":"T-03","installation_id":"ba9f5ff7-b439-4efc-b9ba-24df4df6e65f","install_token":"secret-install-token"}""",
            ),
        )

        val installation = api.enroll(
            BetaEnrollment(
                inviteCode = "BLUE-MANGO",
                section = "Section A",
                plc = "PLC 4",
                consentVersion = "beta-2026-08-15",
                device = BetaDeviceInfo("Samsung", "SM-S928B", "16", "0.2.0-beta", 2),
            ),
        )

        assertEquals("T-03", installation.testerCode)
        val request = server.takeRequest()
        assertEquals("/enroll", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("BLUE-MANGO", body.getString("invite_code"))
        assertEquals("Section A", body.getString("self_section"))
        assertEquals("PLC 4", body.getString("self_plc"))
        assertEquals("SM-S928B", body.getString("model"))
    }

    @Test
    fun `remote stop response blocks auto attendance without becoming a transport failure`() {
        server.enqueue(
            MockResponse().setResponseCode(423).setBody(
                """{"allowed":false,"reason":"remote_stop","checked_at":"2026-08-15T10:00:00Z"}""",
            ),
        )

        val result = api.autoPreflight(
            BetaInstallation("T-03", "ba9f5ff7-b439-4efc-b9ba-24df4df6e65f", "secret-install-token"),
        )

        assertFalse(result.allowed)
        assertEquals("remote_stop", result.reason)
        assertEquals("/auto-preflight", server.takeRequest().path)
    }

    @Test
    fun `event upload includes installation headers and stable event id`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":1}"""))
        val installation = BetaInstallation("T-03", "ba9f5ff7-b439-4efc-b9ba-24df4df6e65f", "token")
        val event = BetaEvent(
            id = "b4661dc9-bc38-477e-bd09-95ce32d7d2d4",
            type = "application_started",
            occurredAt = Instant.parse("2026-08-15T10:00:00Z"),
            payload = mapOf("build_type" to "beta"),
        )

        api.uploadEvents(installation, listOf(event), BetaDeviceInfo("Samsung", "SM-S928B", "16", "0.2.0-beta", 2), true)

        val request = server.takeRequest()
        assertEquals("ba9f5ff7-b439-4efc-b9ba-24df4df6e65f", request.getHeader("x-installation-id"))
        assertEquals("token", request.getHeader("x-install-token"))
        val events = JSONObject(request.body.readUtf8()).getJSONArray("events")
        assertEquals("b4661dc9-bc38-477e-bd09-95ce32d7d2d4", events.getJSONObject(0).getString("event_id"))
        assertTrue(events.getJSONObject(0).getJSONObject("payload").has("build_type"))
    }

    @Test
    fun `tester updates the confirmed beta profile`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"support_name":"Asha Rao","self_section":"Section B","self_plc":"PLC 4","detected_sections":["B"],"detected_groups":["4"]}""",
            ),
        )
        val installation = BetaInstallation("T-03", "ba9f5ff7-b439-4efc-b9ba-24df4df6e65f", "token")

        val profile = api.updateProfile(
            installation,
            BetaProfileUpdate(
                supportName = "Asha Rao",
                selfSection = "Section B",
                selfPlc = "PLC 4",
                detectedSections = setOf("B"),
                detectedGroups = setOf("4"),
                consentVersion = "beta-2026-08-16-profile",
                confirmedAt = Instant.parse("2026-08-16T10:00:00Z"),
            ),
        )

        assertEquals("Asha Rao", profile.supportName)
        val request = server.takeRequest()
        assertEquals("/profile", request.path)
        assertEquals("ba9f5ff7-b439-4efc-b9ba-24df4df6e65f", request.getHeader("x-installation-id"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("Asha Rao", body.getString("support_name"))
        assertEquals("beta-2026-08-16-profile", body.getString("consent_version"))
        assertEquals("B", body.getJSONArray("detected_sections").getString(0))
        assertEquals("4", body.getJSONArray("detected_groups").getString(0))
    }

    @Test
    fun `tester can delete the support name early`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"deleted":true}"""))
        val installation = BetaInstallation("T-03", "ba9f5ff7-b439-4efc-b9ba-24df4df6e65f", "token")

        api.deleteSupportName(installation)

        val request = server.takeRequest()
        assertEquals("/profile-delete", request.path)
        assertEquals("token", request.getHeader("x-install-token"))
    }
}
