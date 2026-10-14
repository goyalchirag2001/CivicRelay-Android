package org.civicrelay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.civicrelay.app.mesh.NearbyMesh

/** User-initiated visible radio service; never stores or unlocks the user's private vault. */
class NearbyForegroundService : Service() {
    companion object {
        private const val CHANNEL = "civicrelay_nearby"
        private const val ID = 31415
        private const val START = "org.civicrelay.app.START_NEARBY"
        private const val STOP = "org.civicrelay.app.STOP_NEARBY"
        private const val EXTRA_MODE = "mode"

        fun start(context: Context, mode: NearbyMesh.Mode) {
            val intent = Intent(context, NearbyForegroundService::class.java)
                .setAction(START).putExtra(EXTRA_MODE, mode.name)
            ContextCompat.startForegroundService(context, intent)
        }
        fun stop(context: Context) {
            context.startService(Intent(context, NearbyForegroundService::class.java).setAction(STOP))
        }
    }
    private val civic get() = (application as CivicRelayApp).civic
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Nearby sharing",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Visible status when you enable offline nearby sharing"
            setShowBadge(false)
        })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            civic.mesh.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != START) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mode = try { NearbyMesh.Mode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: "") }
            catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, NearbyForegroundService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("CivicRelay nearby is on")
            .setContentText("Offline sharing active · Tap to manage")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else startForeground(ID, notification)
            if (mode == NearbyMesh.Mode.ADVERTISE) civic.mesh.startAsAdvertiser()
            else civic.mesh.startAsDiscoverer()
        } catch (_: SecurityException) {
            civic.mesh.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (_: IllegalStateException) {
            civic.mesh.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        // Never pretend that the OS guarantees an everlasting connection or resurrection.
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        civic.mesh.stop()
        super.onDestroy()
    }
}
