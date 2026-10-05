package de.droh.stempeluhr.geo

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import de.droh.stempeluhr.core.StampSource
import de.droh.stempeluhr.data.Settings
import de.droh.stempeluhr.engine.Notifications
import de.droh.stempeluhr.engine.StampEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

object GeofenceManager {
    private const val TAG = "Geofence"
    private const val REQUEST_ID = "arbeit"

    private val _status = MutableStateFlow("Noch nicht registriert")

    /** Letzter Status für die Anzeige in der App. */
    val status: StateFlow<String> = _status

    private var lastStatus: String
        get() = _status.value
        set(v) {
            _status.value = v
        }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, GeofenceReceiver::class.java),
        // Geofencing braucht ein veränderbares PendingIntent, um die Ereignisdaten anzuhängen.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    fun hasPermissions(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Registriert den Geofence neu (oder entfernt ihn, wenn deaktiviert). Mehrfacher Aufruf ist unschädlich. */
    @SuppressLint("MissingPermission")
    fun register(context: Context) {
        val app = context.applicationContext
        val settings = Settings.get(app)
        val client = LocationServices.getGeofencingClient(app)
        val lat = settings.geoLat
        val lon = settings.geoLon
        if (!settings.geoEnabled || lat == null || lon == null) {
            client.removeGeofences(pendingIntent(app))
            lastStatus = if (settings.geoEnabled) "Kein Arbeitsort festgelegt" else "Ausgeschaltet"
            return
        }
        if (!hasPermissions(app)) {
            lastStatus = "Standort-Berechtigung \"Immer erlauben\" fehlt"
            return
        }
        val geofence = Geofence.Builder()
            .setRequestId(REQUEST_ID)
            .setCircularRegion(lat, lon, settings.geoRadius.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .setNotificationResponsiveness(30_000)
            .build()
        val request = GeofencingRequest.Builder()
            // Kein Auslösen beim Registrieren – sonst entstünde ein falsches Kommen zum Registrier-Zeitpunkt.
            .setInitialTrigger(0)
            .addGeofence(geofence)
            .build()
        client.addGeofences(request, pendingIntent(app))
            .addOnSuccessListener { lastStatus = "Aktiv (Radius ${settings.geoRadius} m)" }
            .addOnFailureListener { e ->
                lastStatus = "Fehler: ${e.message}"
                Log.w(TAG, "Geofence konnte nicht registriert werden", e)
            }
    }

    /** Ermittelt den aktuellen Standort (für "Aktuellen Standort übernehmen"). */
    @SuppressLint("MissingPermission")
    fun currentLocation(context: Context, onResult: (Location?) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            onResult(null)
            return
        }
        LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { onResult(it) }
            .addOnFailureListener { onResult(null) }
    }
}

/** Empfängt Betreten/Verlassen des Arbeitsorts von Google Play-Diensten. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                Notifications.warning(
                    context,
                    10,
                    "Geofence nicht verfügbar",
                    "Der Standortdienst wurde ausgeschaltet. Sobald er wieder an ist, wird der Arbeitsort neu überwacht.",
                )
            }
            return
        }
        if (!Settings.get(context).geoEnabled) return
        val now = System.currentTimeMillis()
        // Zeit der auslösenden Standortbestimmung verwenden, sofern plausibel.
        val fixTime = event.triggeringLocation?.time
        val ts = if (fixTime != null && fixTime <= now && abs(now - fixTime) < 30 * 60_000L) fixTime else now
        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> StampEngine.arrive(context, StampSource.GEOFENCE, ts)
            Geofence.GEOFENCE_TRANSITION_EXIT -> StampEngine.leave(context, StampSource.GEOFENCE, ts)
        }
    }
}
