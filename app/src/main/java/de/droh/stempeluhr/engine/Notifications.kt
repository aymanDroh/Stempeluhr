package de.droh.stempeluhr.engine

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.droh.stempeluhr.R
import de.droh.stempeluhr.ui.MainActivity

object Notifications {
    const val CHANNEL_MONITOR = "monitor"
    const val CHANNEL_STAMPS = "stamps"
    const val CHANNEL_WARNINGS = "warnings"

    const val ID_MONITOR = 1
    private const val ID_STAMP = 2
    private const val ID_WARNING_BASE = 100

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "Hintergrund-Erfassung", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Dauerhafte Meldung, solange die automatische Erfassung läuft. " +
                    "Kann in den Systemeinstellungen ausgeblendet werden."
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STAMPS, "Erfasste Stempelzeiten", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Kurze Info bei jedem erfassten Kommen oder Gehen."
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WARNINGS, "Hinweise", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Hinweise, wenn eine Zeit nicht erfasst werden konnte."
            },
        )
    }

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun stamp(context: Context, title: String, text: String) =
        post(context, ID_STAMP, CHANNEL_STAMPS, title, text)

    fun warning(context: Context, key: Int, title: String, text: String) =
        post(context, ID_WARNING_BASE + key, CHANNEL_WARNINGS, title, text)

    private fun post(context: Context, id: Int, channel: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stamp)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // Benachrichtigungen nicht erlaubt – Erfassung läuft trotzdem.
        }
    }
}
