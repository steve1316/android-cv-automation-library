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
 * Contains the utility functions for creating a Notification.
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

        // Channel without banners. Re-posting the notification on it after BANNER_DURATION_MS ends the banner early.
        private const val QUIET_CHANNEL_ID: String = "STATUS_QUIET"
        private const val BANNER_DURATION_MS: Long = 1000L

        // Only schedules the banner collapse, so clearing all of its callbacks never touches anything else.
        private val bannerHandler = Handler(Looper.getMainLooper())

        /**
         * Creates the NotificationChannel and the Notification object.
         *
         * @param context The application context.
         * @param contentClass Class of the Activity to go to when the notification is pressed on.
         * @return Pair object containing the Notification object and its ID string.
         */
        fun getNewNotification(context: Context, contentClass: Class<*>): Pair<Notification, Int> {
            // Create the NotificationChannel.
            createNewNotificationChannel(context)

            // Create the Notification.
            val newNotification = createNewNotification(context, contentClass)

            // Get the NotificationManager and then send the new Notification to it.
            postWithShortBanner(context, newNotification)

            return Pair(newNotification, NOTIFICATION_ID)
        }

        /**
         * Create a new NotificationChannel.
         *
         * https://developer.android.com/training/notify-user/channels
         *
         * @param context The application context.
         */
        private fun createNewNotificationChannel(context: Context) {
            notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Create the NotificationChannel.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channelName = context.getString(R.string.app_name)
                val mChannel = NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_HIGH)
                mChannel.description = "Displays status of $channelName, whether it is running or not."

                // Register the channel with the system; you can't change the importance or other notification behaviors after this.
                notificationManager.createNotificationChannel(mChannel)

                val quietChannel = NotificationChannel(QUIET_CHANNEL_ID, "$channelName (quiet)", NotificationManager.IMPORTANCE_LOW)
                quietChannel.description = "Keeps the status of $channelName in the notification shade after its banner has closed."
                notificationManager.createNotificationChannel(quietChannel)
            }
        }

        /**
         * Create a new Notification.
         *
         * @param context The application context.
         * @param contentClass Class of the Activity to go to when the notification is pressed on.
         * @return A new Notification object.
         */
        private fun createNewNotification(context: Context, contentClass: Class<*>): Notification {
            // Create a PendingIntent to send the user back to the application if they tap the notification itself.
            val contentIntent = Intent(context, contentClass)
            val contentPendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, contentIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Create a STOP Intent for the MediaProjection service.
                val stopIntent = Intent(context, StopServiceReceiver::class.java)

                // Create a PendingIntent in order to add a action button to stop the MediaProjection service in the notification.
                val stopPendingIntent: PendingIntent =
                    PendingIntent.getBroadcast(context, System.currentTimeMillis().toInt(), stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)

                return NotificationCompat.Builder(context, CHANNEL_ID).apply {
                    setSmallIcon(R.drawable.ic_baseline_control_camera_24)
                    setContentTitle("Status")
                    setContentText("Automation is ready to go")
                    setContentIntent(contentPendingIntent)
                    addAction(R.drawable.stop_circle_filled, context.getString(R.string.accessibility_service_action), stopPendingIntent)
                    priority = NotificationManager.IMPORTANCE_HIGH
                    setCategory(Notification.CATEGORY_SERVICE)
                    setOngoing(true)
                    setShowWhen(true)
                }.build()
            } else {
                return NotificationCompat.Builder(context, CHANNEL_ID).apply {
                    setSmallIcon(R.drawable.ic_baseline_control_camera_24)
                    setContentTitle("Status")
                    setContentText("Automation is ready to go")
                    setContentIntent(contentPendingIntent)
                    priority = NotificationManager.IMPORTANCE_HIGH
                    setCategory(Notification.CATEGORY_SERVICE)
                    setOngoing(true)
                    setShowWhen(true)
                }.build()
            }
        }

        /**
         * Updates the Notification content text.
         *
         * @param context The application context.
         * @param contentClass Class of the Activity to go to when the notification is pressed on.
         * @param isRunning Boolean for whether or not the bot process is currently running.
         * @param message Message to append to the Notification text body.
         * @param title Title for the Notification. Defaults to "Status".
         * @param displayBigText Display the big form of the text body template in place of the content text. Defaults to false which will not render it.
         */
        fun updateNotification(context: Context, contentClass: Class<*>, isRunning: Boolean, message: String, title: String = "Status", displayBigText: Boolean = false) {
            var contentText = "Bot process is stopped"
            if (message != "") {
                contentText = message
            } else if (isRunning) {
                contentText = "Bot process is running"
            }

            // Create a PendingIntent to send the user back to the application if they tap the notification itself.
            val contentIntent = Intent(context, contentClass)
            val contentPendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, contentIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            val newNotification =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Create a STOP Intent for the MediaProjection service.
                    val stopIntent = Intent(context, StopServiceReceiver::class.java)

                    // Create a PendingIntent in order to add a action button to stop the MediaProjection service in the notification.
                    val stopPendingIntent: PendingIntent =
                        PendingIntent.getBroadcast(context, System.currentTimeMillis().toInt(), stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)

                    if (displayBigText) {
                        NotificationCompat.Builder(context, CHANNEL_ID).apply {
                            setSmallIcon(R.drawable.ic_baseline_control_camera_24)
                            setContentTitle(title)
                            setContentText("Swipe down to see more...")
                            setStyle(NotificationCompat.BigTextStyle().bigText(message))
                            setContentIntent(contentPendingIntent)
                            addAction(R.drawable.stop_circle_filled, context.getString(R.string.accessibility_service_action), stopPendingIntent)
                            priority = NotificationManager.IMPORTANCE_HIGH
                            setCategory(Notification.CATEGORY_SERVICE)
                            setOngoing(true)
                            setShowWhen(true)
                        }.build()
                    } else {
                        NotificationCompat.Builder(context, CHANNEL_ID).apply {
                            setSmallIcon(R.drawable.ic_baseline_control_camera_24)
                            setContentTitle(title)
                            setContentText(contentText)
                            setContentIntent(contentPendingIntent)
                            addAction(R.drawable.stop_circle_filled, context.getString(R.string.accessibility_service_action), stopPendingIntent)
                            priority = NotificationManager.IMPORTANCE_HIGH
                            setCategory(Notification.CATEGORY_SERVICE)
                            setOngoing(true)
                            setShowWhen(true)
                        }.build()
                    }
                } else {
                    if (displayBigText) {
                        NotificationCompat.Builder(context, CHANNEL_ID).apply {
                            setSmallIcon(R.drawable.ic_baseline_control_camera_24)
                            setContentTitle(title)
                            setContentText("Swipe down to see more...")
                            setStyle(NotificationCompat.BigTextStyle().bigText(message))
                            setContentIntent(contentPendingIntent)
                            priority = NotificationManager.IMPORTANCE_HIGH
                            setCategory(Notification.CATEGORY_SERVICE)
                            setOngoing(true)
                            setShowWhen(true)
                        }.build()
                    } else {
                        NotificationCompat.Builder(context, CHANNEL_ID).apply {
                            setSmallIcon(R.drawable.ic_baseline_control_camera_24)
                            setContentTitle(title)
                            setContentText(contentText)
                            setContentIntent(contentPendingIntent)
                            priority = NotificationManager.IMPORTANCE_HIGH
                            setCategory(Notification.CATEGORY_SERVICE)
                            setOngoing(true)
                            setShowWhen(true)
                        }.build()
                    }
                }

            postWithShortBanner(context, newNotification)
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
            if (!::notificationManager.isInitialized) {
                notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            }
            bannerHandler.removeCallbacksAndMessages(null)
            Log.d(tag, "Attempting to cancel all notifications")
            Log.d(tag, "Active notifications before cancel: ${notificationManager.activeNotifications.size}")
            notificationManager.cancelAll()

            // Log active notifications after cancel.
            val activeNotifications = notificationManager.activeNotifications
            Log.d(tag, "Active notifications after cancel: ${activeNotifications.size}")
            for (notification in activeNotifications) {
                Log.d(tag, "  - ID: ${notification.id}, Tag: ${notification.tag}, Package: ${notification.packageName}")
            }
        }
    }
}
