package org.isdm.companion.platform

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.isdm.companion.CompanionApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonitoringServiceContractTest {
    @Suppress("DEPRECATION")
    @Test
    fun monitoringUsesOnlyTheLocationForegroundServiceType() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, MonitoringService::class.java)
        val service = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            context.packageManager.getServiceInfo(component, 0)
        }

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, service.foregroundServiceType)
    }

    @Suppress("DEPRECATION")
    @Test
    fun appDoesNotRequestTheQuotaLimitedDataSyncForegroundPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }

        assertFalse(app.requestedPermissions.orEmpty().contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
    }

    @Test
    fun stoppingTheCurrentMonitorKeepsFutureAutoAttendanceEnabled() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CompanionApplication
        app.autoAttendanceStore.setEnabled(true)

        app.startService(Intent(app, MonitoringService::class.java).setAction(MonitoringService.ACTION_STOP))
        SystemClock.sleep(250)

        assertTrue(app.autoAttendanceStore.isEnabled())
    }
}
