package me.srishanth.opentrack360.pets.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.os.Build
import me.srishanth.opentrack360.MainActivity

class PetRoutineReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_REMINDER -> {
                val segments = intent.data?.pathSegments ?: return
                val routineId = segments.getOrNull(0) ?: return
                val expectedAt = segments.getOrNull(1)?.toLongOrNull() ?: return
                PetRoutineReminderScheduler(context).onAlarm(routineId, expectedAt)
            }
            Intent.ACTION_BOOT_COMPLETED -> PetRoutineReminderScheduler(context).restore()
        }
    }

    companion object {
        const val ACTION_REMINDER = "me.srishanth.opentrack360.pets.reminders.REMIND"
    }
}

internal object PetRoutineReminderNotifier {
    fun show(context: Context, routineId: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Pet care reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Reminds you when a scheduled pet care task is due"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )
        }
        val openApp = PendingIntent.getActivity(
            context,
            routineId.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Pet care reminder")
            .setContentText("A scheduled care task is due. Open OpenTrack360 to view details.")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify(routineId.hashCode(), notification)
    }

    private const val CHANNEL_ID = "pet_care_reminders"
}
