package com.steve1316.automation_library.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.steve1316.automation_library.R
import com.steve1316.automation_library.data.SharedData

/**
 * Contains the utility functions for the status notification.
 *
 * While a run is in progress the notification follows `BotStatus` and `BotHold` and updates silently. When the run ends it is posted once more
 * with the outcome, on the alerting channel unless the user stopped the bot.
 *
 * Source is from https://github.com/mtsahakis/MediaProjectionDemo where the Java code was converted to Kotlin and additional logic was added to
 * suit this application's purposes.
 */
class NotificationUtils {
    companion object {
        private const val tag: String = "${SharedData.loggerTag}NotificationUtils"

        private lateinit var notificationManager: NotificationManager
        private const val NOTIFICATION_ID: Int = 1
        private const val CHANNEL_ID: String = "STATUS"

        // Channel without banners. Running updates go here, and alerts are re-posted here after BANNER_DURATION_MS to end the banner early.
        private const val QUIET_CHANNEL_ID: String = "STATUS_QUIET"
        private const val BANNER_DURATION_MS: Long = 1000L

        // Only schedules the banner collapse, so clearing all of its callbacks never touches anything else.
        private val bannerHandler = Handler(Looper.getMainLooper())

        // Posts running updates on the main thread. Cleared when the run ends so a late update cannot replace the end notification.
        private val runHandler = Handler(Looper.getMainLooper())

        // Guards the running updates against the end of the run and the final cancel, which happen on other threads.
        private val runLock = Any()

        // Listener registered on BotStatus and BotHold while a run is in progress, or null between runs.
        @Volatile
        private var runListener: (() -> Unit)? = null

        // Last running content posted, so an unchanged update is skipped.
        @Volatile
        private var lastRunContent: StatusContent? = null

        /**
         * Creates the notification channels and the "Ready" notification used to start the foreground service.
         *
         * @param context The application context.
         * @param contentClass Class of the Activity to go to when the notification is pressed on.
         * @return The notification and its ID.
         */
        fun getNewNotification(context: Context, contentClass: Class<*>): Pair<Notification, Int> {
            createNewNotificationChannel(context)
            val newNotification = build(context, contentClass, StatusContentBuilder.ready(), QUIET_CHANNEL_ID, 0L)
            notificationManager.notify(NOTIFICATION_ID, newNotification)
            return Pair(newNotification, NOTIFICATION_ID)
        }

        /**
         * Starts following `BotStatus` and `BotHold`, re-posting the running notification whenever its content changes.
         *
         * @param context The application context.
         * @param contentClass Class of the Activity to go to when the notification is pressed on.
         */
        fun startRunUpdates(context: Context, contentClass: Class<*>) {
            stopRunUpdates()
            val appContext = context.applicationContext
            val listener: () -> Unit = { runHandler.post { postRunning(appContext, contentClass) } }
            runListener = listener
            BotStatus.addListener(listener)
            BotHold.addListener(listener)
            runHandler.post { postRunning(appContext, contentClass) }
        }

        /**
         * Stops following the run. Called when the run ends, before the end notification is posted.
         */
        fun stopRunUpdates() {
            synchronized(runLock) {
                runListener?.let {
                    BotStatus.removeListener(it)
                    BotHold.removeListener(it)
                }
                runListener = null
                lastRunContent = null
                runHandler.removeCallbacksAndMessages(null)
            }
        }

        /**
         * Posts the end-of-run notification from `BotStatus`'s outcome.
         *
         * @param context The application context.
         * @param contentClass Class of the Activity to go to when the notification is pressed on.
         */
        fun postRunEnded(context: Context, contentClass: Class<*>) {
            synchronized(runLock) {
                ensureManager(context)
                val snapshot = BotStatus.snapshot()
                val content = StatusContentBuilder.ended(snapshot)
                if (content.alert) {
                    postWithShortBanner(context, build(context, contentClass, content, CHANNEL_ID, snapshot.elapsedMs))
                } else {
                    bannerHandler.removeCallbacksAndMessages(null)
                    notificationManager.notify(NOTIFICATION_ID, build(context, contentClass, content, QUIET_CHANNEL_ID, snapshot.elapsedMs))
                }
            }
        }

        /**
         * Create the notification channels.
         *
         * https://developer.android.com/training/notify-user/channels
         *
         * @param context The application context.
         */
        private fun createNewNotificationChannel(context: Context) {
            ensureManager(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channelName = context.getString(R.string.app_name)
                val mChannel = NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_HIGH)
                mChannel.description = "Alerts when $channelName finishes, stops on its own, or crashes."

                // Register the channel with the system; you can't change the importance or other notification behaviors after this.
                notificationManager.createNotificationChannel(mChannel)

                val quietChannel = NotificationChannel(QUIET_CHANNEL_ID, "$channelName (quiet)", NotificationManager.IMPORTANCE_LOW)
                quietChannel.description = "Shows the live status of $channelName while it runs."
                notificationManager.createNotificationChannel(quietChannel)
            }
        }

        /**
         * Gets the NotificationManager the first time it is needed.
         *
         * @param context The application context.
         */
        private fun ensureManager(context: Context) {
            if (!::notificationManager.isInitialized) {
                notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            }
        }

        /**
         * Posts the running notification unless the run has ended or nothing changed.
         *
         * @param context The application context.
         * @param contentClass Class of the Activity to go to when the notification is pressed on.
         */
        private fun postRunning(context: Context, contentClass: Class<*>) {
            synchronized(runLock) {
                if (runListener == null) return
                val snapshot = BotStatus.snapshot()
                if (snapshot.outcome != null) return
                val content = StatusContentBuilder.running(snapshot, BotHold.pauseState, BotHold.hasSafePoint)
                if (content == lastRunContent) return
                lastRunContent = content
                ensureManager(context)
                bannerHandler.removeCallbacksAndMessages(null)
                notificationManager.notify(NOTIFICATION_ID, build(context, contentClass, content, QUIET_CHANNEL_ID, snapshot.elapsedMs))
            }
        }

        /**
         * Builds a notification from its content.
         *
         * @param context The application context.
         * @param contentClass Class of the Activity to go to when the notification is pressed on.
         * @param content What the notification says and which controls it has.
         * @param channelId The channel to post on.
         * @param elapsedMs Running time, used as the chronometer's starting point.
         * @return The notification.
         */
        private fun build(context: Context, contentClass: Class<*>, content: StatusContent, channelId: String, elapsedMs: Long): Notification {
            val contentIntent = Intent(context, contentClass)
            val contentPendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, contentIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            return NotificationCompat.Builder(context, channelId).apply {
                setSmallIcon(R.drawable.ic_baseline_control_camera_24)
                setContentTitle(content.title)
                setContentText(content.text)
                setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
                setContentIntent(contentPendingIntent)
                setCategory(Notification.CATEGORY_SERVICE)
                setOngoing(true)
                setOnlyAlertOnce(!content.alert)
                priority = if (content.alert) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW
                content.subText?.let { setSubText(it) }
                content.progress?.let { (current, max) -> setProgress(max, current, false) }
                setShowWhen(content.chronometer || content.alert)
                if (content.chronometer) {
                    setUsesChronometer(true)
                    setWhen(System.currentTimeMillis() - elapsedMs)
                }

                // Notification action buttons are only added from Android 8.0, as before.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    when (content.pauseAction) {
                        PauseAction.PAUSE -> addAction(R.drawable.pause_circle_filled, "Pause", pausePendingIntent(context, PauseActionReceiver.ACTION_PAUSE, 2))
                        PauseAction.RESUME -> addAction(R.drawable.play_circle_filled, "Resume", pausePendingIntent(context, PauseActionReceiver.ACTION_RESUME, 3))
                        PauseAction.NONE -> {}
                    }
                    if (content.showStop) {
                        // Stops the MediaProjection service through StopServiceReceiver, as before.
                        val stopIntent = Intent(context, StopServiceReceiver::class.java)
                        val stopPendingIntent: PendingIntent = PendingIntent.getBroadcast(context, 4, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                        addAction(R.drawable.stop_circle_filled, context.getString(R.string.accessibility_service_action), stopPendingIntent)
                    }
                }
            }.build()
        }

        /**
         * Builds the PendingIntent for the Pause or Resume action.
         *
         * @param context The application context.
         * @param action `PauseActionReceiver.ACTION_PAUSE` or `PauseActionReceiver.ACTION_RESUME`.
         * @param requestCode Request code that keeps the Pause and Resume PendingIntents apart.
         * @return The PendingIntent.
         */
        private fun pausePendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, PauseActionReceiver::class.java).setAction(action)
            return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /**
         * Posts the notification with its banner, then re-posts the same notification on the quiet channel after `BANNER_DURATION_MS`. A notification
         * that no longer qualifies for a banner is taken out of the banner, while it stays in the notification shade. Apps cannot set the banner
         * duration directly, so this keeps it short. Channels only exist from Android 8.0, so older versions keep the system banner.
         *
         * @param context The application context.
         * @param notification The notification to post on the alerting channel.
         */
        private fun postWithShortBanner(context: Context, notification: Notification) {
            // A newer post replaces the notification, so drop the pending collapse of the previous one.
            bannerHandler.removeCallbacksAndMessages(null)
            notificationManager.notify(NOTIFICATION_ID, notification)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

            bannerHandler.postDelayed({
                // Do not bring the notification back if it was cancelled in the meantime, e.g. because the services stopped.
                if (notificationManager.activeNotifications.none { it.id == NOTIFICATION_ID }) return@postDelayed
                val quietNotification = Notification.Builder.recoverBuilder(context, notification).setChannelId(QUIET_CHANNEL_ID).build()
                notificationManager.notify(NOTIFICATION_ID, quietNotification)
            }, BANNER_DURATION_MS)
        }

        /**
         * Cancels all notifications and removes them from the notification shade.
         * Should be called when the service is being fully stopped/dismissed.
         *
         * @param context The application context.
         */
        fun cancelAllNotifications(context: Context) {
            ensureManager(context)
            synchronized(runLock) {
                stopRunUpdates()
                bannerHandler.removeCallbacksAndMessages(null)
                Log.d(tag, "Attempting to cancel all notifications")
                Log.d(tag, "Active notifications before cancel: ${notificationManager.activeNotifications.size}")
                notificationManager.cancelAll()
            }

            // Log active notifications after cancel.
            val activeNotifications = notificationManager.activeNotifications
            Log.d(tag, "Active notifications after cancel: ${activeNotifications.size}")
            for (notification in activeNotifications) {
                Log.d(tag, "  - ID: ${notification.id}, Tag: ${notification.tag}, Package: ${notification.packageName}")
            }
        }
    }
}
