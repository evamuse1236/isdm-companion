package org.isdm.companion.platform

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.isdm.companion.CompanionApplication
import org.isdm.companion.engine.Command
import org.isdm.companion.engine.MonitoringMode
import org.isdm.companion.ui.MainActivity

class MonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    private val engine
        get() = (application as CompanionApplication).engine

    override fun onCreate() {
        super.onCreate()
        AndroidNotifier.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                engine.dispatch(Command.DisarmMonitoring)
                stopMonitoring()
            }
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            monitorNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        if (monitorJob?.isActive != true) {
            monitorJob = scope.launch {
                while (isActive) {
                    val result = engine.dispatch(Command.MonitorTick)
                    if (!engine.state.value.monitor.active) {
                        stopMonitoring()
                        break
                    }
                    delay(POLL_MS)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.launch {
            engine.dispatch(Command.SystemLimitReached)
            stopMonitoring()
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun monitorNotification(): Notification {
        val mode = engine.state.value.monitor.mode
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, MonitoringService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, AndroidNotifier.MONITOR_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(
                if (mode == MonitoringMode.AUTO_MARK) "ISDM Companion auto-mark is armed"
                else "ISDM Companion is monitoring today",
            )
            .setContentText(
                if (mode == MonitoringMode.AUTO_MARK) "Auto-mark · checking every 30 seconds · stops at 6:00 PM"
                else "Notify-only · checking every 30 seconds · stops at 6:00 PM",
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val NOTIFICATION_ID = 10_001
        const val ACTION_STOP = "org.isdm.companion.STOP_MONITORING"
        private const val POLL_MS = 30_000L
    }
}
