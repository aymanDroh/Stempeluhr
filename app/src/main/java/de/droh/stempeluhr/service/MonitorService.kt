package de.droh.stempeluhr.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.droh.stempeluhr.R
import de.droh.stempeluhr.core.StampSource
import de.droh.stempeluhr.data.Settings
import de.droh.stempeluhr.engine.Notifications
import de.droh.stempeluhr.engine.StampEngine
import de.droh.stempeluhr.geo.GeofenceManager

/**
 * Dauerhaft laufender Hintergrunddienst:
 * - erkennt Verbinden/Trennen mit dem Arbeits-WLAN (auch bei gesperrtem Handy),
 * - hält die App am Leben, damit verzögerte Gehen-Meldungen pünktlich gespeichert werden,
 * - registriert den Geofence neu, wenn der Standortdienst wieder eingeschaltet wird.
 */
class MonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Netzwerk -> ist Arbeits-WLAN. */
    private val wifiNetworks = mutableMapOf<Network, Boolean>()

    private val tickRunnable = object : Runnable {
        override fun run() {
            StampEngine.tick(this@MonitorService)
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val locationModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            GeofenceManager.register(context)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        goForeground()
        ContextCompat.registerReceiver(
            this,
            locationModeReceiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        handler.post(tickRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = Settings.get(this)
        if (!settings.wifiEnabled && !settings.geoEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        StampEngine.tick(this)
        restartWifiMonitoring()
        GeofenceManager.register(this)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopWifiMonitoring()
        try {
            unregisterReceiver(locationModeReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    private fun goForeground() {
        val settings = Settings.get(this)
        val parts = buildList {
            if (settings.wifiEnabled) add("WLAN")
            if (settings.geoEnabled) add("Standort")
        }
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stamp)
            .setContentTitle("Stempeluhr läuft")
            .setContentText("Automatische Erfassung: ${parts.joinToString(" + ").ifEmpty { "aus" }}")
            .setContentIntent(Notifications.openAppIntent(this))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifications.ID_MONITOR, notification, type)
    }

    // ---------- WLAN ----------

    private fun restartWifiMonitoring() {
        stopWifiMonitoring()
        val settings = Settings.get(this)
        if (!settings.wifiEnabled || settings.wifiSsidSet.isEmpty()) return

        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = if (Build.VERSION.SDK_INT >= 31) {
            // Ohne diese Option liefert Android den WLAN-Namen nicht mit.
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    onWifiCapabilities(network, caps)

                override fun onLost(network: Network) = onWifiLost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    onWifiCapabilities(network, caps)

                override fun onLost(network: Network) = onWifiLost(network)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        cm.registerNetworkCallback(request, callback, handler)
        networkCallback = callback

        // Wenn laut gespeichertem Zustand anwesend, aber nach dem Start keine Arbeits-WLAN-Verbindung
        // gemeldet wird, wurde das Gehen verpasst (Dienst/Handy war aus).
        handler.postDelayed({
            if (wifiNetworks.values.none { it }) StampEngine.markLost(this, StampSource.WLAN)
        }, STARTUP_CHECK_MS)
    }

    private fun stopWifiMonitoring() {
        networkCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        networkCallback = null
        wifiNetworks.clear()
    }

    private fun onWifiCapabilities(network: Network, caps: NetworkCapabilities) {
        val ssid = currentSsid(caps) ?: return // Name unbekannt (z. B. Standort aus) – nichts entscheiden.
        val isWork = ssid in Settings.get(this).wifiSsidSet
        val wasWork = wifiNetworks[network] == true
        wifiNetworks[network] = isWork
        if (isWork && !wasWork) {
            StampEngine.arrive(this, StampSource.WLAN)
        } else if (!isWork && wasWork && wifiNetworks.values.none { it }) {
            StampEngine.leave(this, StampSource.WLAN)
        }
    }

    private fun onWifiLost(network: Network) {
        val wasWork = wifiNetworks.remove(network) == true
        if (wasWork && wifiNetworks.values.none { it }) {
            StampEngine.leave(this, StampSource.WLAN)
        }
    }

    @Suppress("DEPRECATION")
    private fun currentSsid(caps: NetworkCapabilities): String? {
        val info = if (Build.VERSION.SDK_INT >= 31) {
            caps.transportInfo as? WifiInfo
        } else {
            applicationContext.getSystemService(WifiManager::class.java).connectionInfo
        }
        return cleanSsid(info?.ssid)
    }

    companion object {
        private const val TAG = "MonitorService"
        private const val TICK_MS = 60_000L
        private const val STARTUP_CHECK_MS = 30_000L

        fun cleanSsid(raw: String?): String? {
            if (raw == null) return null
            val s = raw.removeSurrounding("\"")
            return if (s.isBlank() || s == "<unknown ssid>") null else s
        }

        /** Startet den Dienst, falls WLAN oder Geofence eingeschaltet ist, sonst wird er beendet. */
        fun start(context: Context) {
            val settings = Settings.get(context)
            val intent = Intent(context, MonitorService::class.java)
            if (!settings.wifiEnabled && !settings.geoEnabled) {
                context.stopService(intent)
                GeofenceManager.register(context)
                return
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Android kann den Start aus dem Hintergrund verweigern (z. B. Akku-Optimierung aktiv).
                Log.w(TAG, "Dienst konnte nicht gestartet werden", e)
                GeofenceManager.register(context)
            }
        }
    }
}
