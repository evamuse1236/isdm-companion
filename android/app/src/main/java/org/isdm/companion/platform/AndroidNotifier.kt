package org.isdm.companion.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.isdm.companion.engine.NotificationEvent
import org.isdm.companion.engine.Notifier
import org.isdm.companion.ui.MainActivity

class AndroidNotifier(private val context: Context) : Notifier {
    override suspend fun notify(event: NotificationEvent) {
        when (event) {
            is NotificationEvent.ClassOpen -> showClassOpen(event)
            is NotificationEvent.MarkedPresent -> showResult(
                event.session.nid.orEmpty(),
                "Marked present: ${event.session.name}",
                event.session.room ?: "Attendance confirmed by the LMS.",
            )
            is NotificationEvent.MarkFailed -> showResult(
                event.session.nid.orEmpty(),
                "Could not mark: ${event.session.name}",
                "Open ISDM Companion to check the marking window.",
            )
            is NotificationEvent.MonitoringStopped -> {
                NotificationManagerCompat.from(context).cancel(MonitoringService.NOTIFICATION_ID)
            }
            is NotificationEvent.MonitoringArmed -> Unit
        }
    }

    private fun showClassOpen(event: NotificationEvent.ClassOpen) {
        val session = event.session
        val nid = session.nid ?: return
        val openIntent = PendingIntent.getActivity(
            context,
            nid.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_MARK_NID, nid)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val where = listOfNotNull(session.room, session.floorLabel).joinToString(" · ")
        val notification = NotificationCompat.Builder(context, ATTENDANCE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Mark attendance: ${session.name}")
            .setContentText(where.ifBlank { "The LMS marking window is open." })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(0, "Mark present", openIntent)
            .build()
        notifySafely(ATTENDANCE_BASE_ID + nid.hashCode().and(0x0fff), notification)
    }

    private fun showResult(nid: String, title: String, body: String) {
        val openIntent = PendingIntent.getActivity(
            context,
            nid.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ATTENDANCE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        notifySafely(RESULT_BASE_ID + nid.hashCode().and(0x0fff), notification)
    }

    private fun notifySafely(id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // The engine result remains authoritative when notification permission is denied.
        }
    }

    companion object {
        const val MONITOR_CHANNEL = "class_day_monitor"
        const val ATTENDANCE_CHANNEL = "attendance_alerts"
        private const val ATTENDANCE_BASE_ID = 20_000
        private const val RESULT_BASE_ID = 30_000

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannels(
                listOf(
                    NotificationChannel(
                        MONITOR_CHANNEL,
                        "Class-day monitoring",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "Shows when ISDM Companion is actively checking the LMS."
                    },
                    NotificationChannel(
                        ATTENDANCE_CHANNEL,
                        "Attendance alerts",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "Alerts when an LMS attendance window opens."
                    },
                ),
            )
        }
    }
}
