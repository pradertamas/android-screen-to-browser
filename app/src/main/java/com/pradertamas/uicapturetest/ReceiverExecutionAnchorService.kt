package com.pradertamas.uicapturetest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Execution anchor only. Never constructs a receiver, owns media, or restores a dead process. */
class ReceiverExecutionAnchorService : Service() {
    companion object {
        private const val KEY = "receiverAnchor"
        private const val CHANNEL = "receiver-active"
        private const val NOTIFICATION = 6
        @Volatile private var foregroundKey: String? = null // Diagnostic observation, never authority.
        internal fun foregroundFor(id: ReceiverSessionIdentity?) = id != null && foregroundKey == id.anchorKey
        internal fun request(context: Context, id: ReceiverSessionIdentity) {
            context.startForegroundService(Intent(context, ReceiverExecutionAnchorService::class.java)
                .setAction("START").putExtra(KEY, id.anchorKey))
        }
        internal fun notificationPermissionGranted(context: Context, id: ReceiverSessionIdentity) {
            // Update an already attached notification; this command cannot create an anchor.
            runCatching { context.startService(Intent(context, ReceiverExecutionAnchorService::class.java)
                .setAction("REFRESH").putExtra(KEY, id.anchorKey)) }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var lifecycle: ReceiverAnchorLifecycle
    private var instanceKey: String? = null
    private fun trace(event: String, key: String? = instanceKey) {
        Log.i("ReceiverLifecycle", "role=RECEIVE component=ReceiverExecutionAnchorService anchor=${key?.take(8) ?: "none"} $event")
    }
    override fun onCreate() {
        super.onCreate()
        trace("anchor-service-created")
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Receiving screen share", NotificationManager.IMPORTANCE_LOW))
        lifecycle = ReceiverAnchorLifecycle(ReceiverSessions::anchorCurrent, { key ->
            startForeground(NOTIFICATION, notification(key), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            instanceKey = key; foregroundKey = key
            trace("anchor-foreground-established type=specialUse", key)
        }, ReceiverSessions::stopAnchoredSession, { startId ->
            if (foregroundKey == instanceKey) foregroundKey = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }, ::trace, { key ->
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(key))
        })
        scope.launch { ReceiverSessions.state.collect { lifecycle.changed() } }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ReceiverSessions.checkExpiry()
        lifecycle.command(intent?.action, intent?.getStringExtra(KEY), startId)
        return START_NOT_STICKY // No redelivery/reconstruction of a process-local WebRTC session.
    }
    private fun notification(key: String): Notification {
        val stop = PendingIntent.getService(this, 0,
            Intent(this, ReceiverExecutionAnchorService::class.java).setAction("STOP")
                .setIdentifier(key).putExtra(KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, NOTIFICATION,
            Intent(this, MainActivity::class.java).setIdentifier("receiver-anchor-open")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Receiving screen share")
            .setContentText("Screen sharing is active")
            .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
    override fun onDestroy() {
        scope.cancel()
        if (foregroundKey == instanceKey) foregroundKey = null
        trace("anchor-service-destroyed")
        super.onDestroy() // Unexpected service destruction does not dispose the receiver owner.
    }
}
