package org.isdm.companion.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceLocationGateTest {
    private val now = Instant.parse("2026-08-11T09:00:00Z")
    private val gate = AttendanceLocationGate()

    @Test
    fun allowsFreshEvidenceAtTheCampusCentreWithOneHundredMeterAccuracy() {
        val decision = gate.evaluate(evidence(accuracyMeters = 100.0), now)

        assertTrue(decision.allowsMark)
        assertEquals(null, decision.reason)
    }

    @Test
    fun deniesEvidenceOnTheCampusZoneBoundaryWhenItHasNonzeroAccuracy() {
        // 250 m north of the campus centre using the mean Earth radius.
        val decision = gate.evaluate(evidence(latitude = 28.61586225, longitude = 77.36098275), now)

        assertFalse(decision.allowsMark)
        assertEquals(LocationGateReason.INACCURATE_EVIDENCE, decision.reason)
    }

    @Test
    fun deniesEvidenceOutsideTheCampusZone() {
        val decision = gate.evaluate(evidence(latitude = 28.6160, longitude = 77.36098275), now)

        assertFalse(decision.allowsMark)
        assertEquals(LocationGateReason.OUTSIDE_CAMPUS_ZONE, decision.reason)
    }

    @Test
    fun deniesMissingEvidence() {
        val decision = gate.evaluate(evidence = null, now = now)

        assertFalse(decision.allowsMark)
        assertEquals(LocationGateReason.MISSING_EVIDENCE, decision.reason)
    }

    @Test
    fun deniesStaleEvidence() {
        val decision = gate.evaluate(evidence(observedAt = now.minusSeconds(121)), now)

        assertFalse(decision.allowsMark)
        assertEquals(LocationGateReason.STALE_EVIDENCE, decision.reason)
    }

    @Test
    fun allowsFreshEvidenceCapturedJustAfterEvaluationBegan() {
        val decision = gate.evaluate(evidence(observedAt = now.plusSeconds(1)), now)

        assertTrue(decision.allowsMark)
        assertEquals(null, decision.reason)
    }

    @Test
    fun deniesEvidenceFarInTheFuture() {
        val decision = gate.evaluate(evidence(observedAt = now.plusSeconds(16)), now)

        assertFalse(decision.allowsMark)
        assertEquals(LocationGateReason.STALE_EVIDENCE, decision.reason)
    }

    @Test
    fun deniesInaccurateEvidence() {
        val decision = gate.evaluate(evidence(accuracyMeters = 250.1), now)

        assertFalse(decision.allowsMark)
        assertEquals(LocationGateReason.INACCURATE_EVIDENCE, decision.reason)
    }

    @Test
    fun deniesEvidenceWhoseAccuracyCircleExtendsPastTheCampusZone() {
        // The point is 230 m north of centre, so its 25 m accuracy circle leaves the zone.
        val decision = gate.evaluate(
            evidence(latitude = 28.61568239, longitude = 77.36098275, accuracyMeters = 25.0),
            now,
        )

        assertFalse(decision.allowsMark)
        assertEquals(LocationGateReason.INACCURATE_EVIDENCE, decision.reason)
    }

    @Test
    fun deniesInvalidOrMockEvidence() {
        val invalidAccuracy = gate.evaluate(evidence(accuracyMeters = -1.0), now)
        val mockEvidence = gate.evaluate(evidence(isMock = true), now)

        assertEquals(LocationGateReason.INACCURATE_EVIDENCE, invalidAccuracy.reason)
        assertEquals(LocationGateReason.MOCK_LOCATION_EVIDENCE, mockEvidence.reason)
    }

    private fun evidence(
        latitude: Double = 28.61361395,
        longitude: Double = 77.36098275,
        accuracyMeters: Double = 20.0,
        observedAt: Instant = now,
        isMock: Boolean = false,
    ) = LocationEvidence(latitude, longitude, accuracyMeters, observedAt, isMock)
}
