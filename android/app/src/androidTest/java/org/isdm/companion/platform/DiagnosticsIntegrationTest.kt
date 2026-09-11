package org.isdm.companion.platform

import android.os.StrictMode
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.isdm.companion.CompanionApplication
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class DiagnosticsIntegrationTest {
    private val app get() = ApplicationProvider.getApplicationContext<CompanionApplication>()

    @Test fun localLogWritesOffMainThreadAndExportsQueuedEvents() = runBlocking {
        val violations = CopyOnWriteArrayList<String>()
        val marker = "integration_${System.nanoTime()}"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val previous = StrictMode.getThreadPolicy()
            try {
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                    .detectDiskWrites().detectDiskReads()
                    .penaltyListener({ it.run() }) { violations.add(it.toString()) }.build())
                app.diagnostics.log(marker, mapOf("duration_ms" to "72"))
            } finally { StrictMode.setThreadPolicy(previous) }
        }
        val export = app.localDiagnostics.snapshot()
        assertTrue(export.contains(marker))
        assertTrue(export.contains("duration_ms=72"))
        assertTrue(export.contains("Android SDK"))
        assertTrue(violations.toString(), violations.isEmpty())
    }

    @Test fun crashHandlerPreservesAndroidHandlerAndStoresSanitizedFrames() = runBlocking {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var delegated = false
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated = true }
            app.localDiagnostics.installCrashHandler()
            val exception = IllegalStateException("unlabelled-private-crash-message")
            exception.stackTrace = arrayOf(StackTraceElement("org.isdm.companion.TestProbe", "fail", "TestProbe.kt", 19))
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), exception)
            assertTrue(delegated)
            val export = app.localDiagnostics.snapshot()
            assertTrue(export.contains("uncaught_exception"))
            assertTrue(export.contains("org.isdm.companion.TestProbe.fail:19"))
            assertFalse(export.contains("unlabelled-private-crash-message"))
        } finally { Thread.setDefaultUncaughtExceptionHandler(original) }
    }
}
