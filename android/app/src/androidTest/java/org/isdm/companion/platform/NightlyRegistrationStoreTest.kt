package org.isdm.companion.platform

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightlyRegistrationStoreTest {
    @Test fun pendingIdentitySurvivesStoreRecreationAndIsSavedAfterSuccess() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("nightly-fixture-$name", mode)
        }
        try {
            val pending = BetaInstallationStore(context).pendingNightlyRegistration()
            assertTrue(pending.installToken.matches(Regex("[a-f0-9]{64}")))
            assertEquals(pending, BetaInstallationStore(context).pendingNightlyRegistration())
            val enrolled = pending.copy(testerCode = "N-000001")
            assertTrue(BetaInstallationStore(context).save(enrolled))
            assertEquals(enrolled, BetaInstallationStore(context).load())
        } finally { base.deleteSharedPreferences("nightly-fixture-beta_installation") }
    }
}
