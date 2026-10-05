package de.droh.stempeluhr.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.droh.stempeluhr.engine.Notifications

/** Startet die Erfassung nach einem Neustart des Handys oder nach einem App-Update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                Notifications.createChannels(context)
                // Startet den Dienst und registriert dabei auch den Geofence neu
                // (Android löscht Geofences beim Neustart).
                MonitorService.start(context)
            }
        }
    }
}
