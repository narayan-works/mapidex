package com.narayan.mapidex.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * Foreground service that owns the LAN HTTP server + GPS updates, so /feed.json keeps
 * serving when the app is backgrounded or the screen is off.
 */
class MapidexService : Service() {

    private var server: FeedServer? = null
    private var locationManager: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val locationListener = LocationListener { onLocation(it) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (server != null) return START_STICKY

        try {
            goForeground()
        } catch (e: Exception) {
            error = "Could not start foreground service: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }

        History.init(applicationContext)        // ask_log.json / detour_log.json
        CaptureStore.init(applicationContext)   // <filesDir>/captures — same dir as FeedServer
        AskEngine.prepare()                     // AICore status + Nano download/warmup

        try {
            val s = FeedServer(java.io.File(filesDir, "captures"))
            s.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = s
        } catch (e: Exception) {
            error = "Could not start server: ${e.message}"
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        acquireLocks()
        startLocationUpdates()
        error = null
        running = true
        return START_STICKY
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mapidex server", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MapidexService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Mapidex serving on :${FeedServer.PORT}")
            .setContentText("GET /feed.json · keep phone on the Playdate's Wi‑Fi")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (hasLocation()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mapidex:server").apply {
            setReferenceCounted(false); acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "mapidex:server").apply {
            setReferenceCounted(false); acquire()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocation()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.let { onLocation(it) }
        providers.forEach {
            runCatching { lm.requestLocationUpdates(it, 2000L, 1f, locationListener, Looper.getMainLooper()) }
        }
    }

    private fun onLocation(loc: Location) {
        FeedState.update(loc.latitude, loc.longitude)
    }

    private fun shutdown() {
        server?.stop()
        server = null
        locationManager?.removeUpdates(locationListener)
        locationManager = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.narayan.mapidex.companion.STOP"
        private const val CHANNEL_ID = "mapidex_server"
        private const val NOTIF_ID = 8080

        /** Observable from Compose so the in-app button follows the notification's Stop action. */
        var running by mutableStateOf(false)
            private set
        var error by mutableStateOf<String?>(null)

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MapidexService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MapidexService::class.java).setAction(ACTION_STOP))
        }
    }
}
