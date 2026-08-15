package org.isdm.companion.platform

import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LmsBrowserSessionTest {
    @Test
    fun clearingTheLmsSessionRemovesWebViewCookies() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cookies = CookieManager.getInstance()
        instrumentation.runOnMainSync {
            cookies.setCookie(LMS_URL, "session=test; Secure")
            cookies.flush()
        }
        assertNotNull(cookies.getCookie(LMS_URL))

        val cleared = CountDownLatch(1)
        instrumentation.runOnMainSync { clearLmsBrowserSession { cleared.countDown() } }

        assertTrue(cleared.await(5, TimeUnit.SECONDS))
        assertFalse(cookies.hasCookies())
    }

    private companion object {
        const val LMS_URL = "https://lms.isdm.org.in/"
    }
}
