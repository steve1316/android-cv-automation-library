package com.steve1316.automation_library.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.steve1316.automation_library.R

/**
 * Contains the utility functions for creating a Notification.
 *
 * Source is from https://github.com/mtsahakis/MediaProjectionDemo where the Java code was converted to Kotlin and additional logic was added to
 * suit this application's purposes.
 */
class NotificationUtils {
	companion object {
		private lateinit var notificationManager: NotificationManager
		private const val NOTIFICATION_ID: Int = 1
		private const val CHANNEL_ID: String = "STATUS"

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
			notificationManager.notify(NOTIFICATION_ID, newNotification)

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
		 * @param message Message to append to the Notification text body.
		 * @param title Title for the Notification. Defaults to "Status".
		 * @param displayBigText Display the big form of the text body template in place of the content text. Defaults to false which will not render it.
		 */
		fun updateNotification(context: Context, contentClass: Class<*>, message: String, title: String = "Status", displayBigText: Boolean = false) {
			// Create a PendingIntent to send the user back to the application if they tap the notification itself.
			val contentIntent = Intent(context, contentClass)
			val contentPendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, contentIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

			val newNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
						setContentText(message)
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
						setContentText(message)
						setContentIntent(contentPendingIntent)
						priority = NotificationManager.IMPORTANCE_HIGH
						setCategory(Notification.CATEGORY_SERVICE)
						setOngoing(true)
						setShowWhen(true)
					}.build()
				}
			}

			notificationManager.notify(NOTIFICATION_ID, newNotification)
		}
	}
}