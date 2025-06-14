package de.qrdn.phy2music;

import android.app.IntentService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat


// receives "BOOT_COMPLETED" message via AndroidManifest
// see https://medium.com/make-android/start-android-activity-or-service-from-the-background-689f49167d80
// https://stackoverflow.com/a/7690600
class StartupReceiver : BroadcastReceiver() {
    private val LogTag = "phy2music_StartupReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            Log.d(LogTag, "boot complete")
            context.startService(
                Intent(context, NotificationService::class.java)
                    .putExtra("caller", "BOOT_COMPLETE")
            )
        }
    }
}

//TODO: IntentService is deprecated, use WorkManager
class NotificationService : IntentService("phy2musicNotificationService") {
    private val LogTag: String = "phy2music_NotificationService"
    private val CHANNEL_ID: String = "99f50b64-ba06-4b4c-a721-afeecce7729a"

    override fun onCreate() {
        super.onCreate()
        Log.d(LogTag, "Starting")
        createNotificationChannel()
    }

    override fun onDestroy() {
        Log.d(LogTag, "Exiting")
        super.onDestroy()
    }

    override fun onHandleIntent(intent: Intent?) {
        val intentType = intent?.extras?.getString("caller");
        Log.d(LogTag, "received intent with caller $intentType: $intent")
        if (intentType == null) {
            return
        }
        when (intentType) {
            "BOOT_COMPLETE" -> {
                initPermanentNotification()
            }
            "MainActivityStarted" -> {
                initPermanentNotification()
            }
        }
    }

    private fun createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is not in the Support Library.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Persistent Notification",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        // Register the channel with the system.
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun initPermanentNotification() {
        // https://stackoverflow.com/a/19630079
        // https://developer.android.com/develop/ui/views/notifications/build-notification

        val pIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra("caller", "tap_notification_scan")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        /*
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_camera,
            "Scan QR code",
            pIntent).build()
         */

        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentTitle("Scan \uD83D\uDCF7 Code to Play Music \uD83C\uDFB6")
            //.setContentText("Details")
            .setContentIntent(pIntent)  // needs unlock
            //TODO: open (MainActivity+)BarcodeScan from notification on lockscreen without unlock
            // to that end, use RemoteViews (but needs custom layout?) as per https://stackoverflow.com/questions/33724567/how-to-perform-notification-action-click-on-lock-screen https://stackoverflow.com/questions/22789588/how-to-update-notification-with-remoteviews
            //.setFullScreenIntent(pIntent, true)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setAutoCancel(false)
            //.addAction(action)
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(0, n)
    }
}
