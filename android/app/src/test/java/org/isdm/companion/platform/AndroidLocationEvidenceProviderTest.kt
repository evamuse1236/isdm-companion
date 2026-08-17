package org.isdm.companion.platform

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidLocationEvidenceProviderTest {
    @Test
    fun `cold fix policy requests fine high-power location for thirty seconds`() {
        val policy = LOCATION_ACQUISITION_POLICY

        assertEquals(true, policy.requireFineAccuracy)
        assertEquals(true, policy.preferHighPower)
        assertEquals(Duration.ofSeconds(30), policy.timeout)
    }
}
