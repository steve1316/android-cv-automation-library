package com.steve1316.automation_library.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.steve1316.automation_library.data.SharedData

/**
 * BroadcastReceiver to detect when the device screen turns on or off.
 * This will gracefully stop the bot when the device goes to sleep to prevent the bot from continuing to execute gestures on a sleeping device.
 * 
 * Must be registered dynamically in your app's MainActivity.
 */
class ScreenStateReceiver : BroadcastReceiver() {
	companion object {
		private val tag: String = "${SharedData.loggerTag}ScreenStateReceiver"
		private var receiver: ScreenStateReceiver? = null
		private var isRegistered = false

		/**
		 * Register this receiver dynamically at runtime.
		 * Must be called from the app that uses this library (typically in onCreate).
		 *
		 * @param context The application context.
		 */
		fun register(context: Context) {
			if (isRegistered) {
				Log.d(tag, "ScreenStateReceiver is already registered. Skipping registration.")
				return
			}

			try {
				receiver = ScreenStateReceiver()
				val filter = IntentFilter().apply {
					addAction(Intent.ACTION_SCREEN_OFF)
					addAction(Intent.ACTION_SCREEN_ON)
				}

				context.registerReceiver(receiver, filter)
				isRegistered = true
				Log.d(tag, "ScreenStateReceiver registered successfully.")
			} catch (e: Exception) {
				Log.e(tag, "Error registering ScreenStateReceiver: ${e.message}", e)
			}
		}

		/**
		 * Unregister this receiver.
		 * Should be called from the app that uses this library (typically in onDestroy).
		 *
		 * @param context The application context.
		 */
		fun unregister(context: Context) {
			if (!isRegistered || receiver == null) {
				Log.d(tag, "ScreenStateReceiver is not registered. Skipping unregistration.")
				return
			}

			try {
				context.unregisterReceiver(receiver)
				receiver = null
				isRegistered = false
				Log.d(tag, "ScreenStateReceiver unregistered successfully.")
			} catch (e: Exception) {
				Log.e(tag, "Error unregistering ScreenStateReceiver: ${e.message}")
			}
		}
	}

	override fun onReceive(context: Context, intent: Intent?) {
		when (intent?.action) {
			Intent.ACTION_SCREEN_OFF -> {
				Log.d(tag, "Screen turned off detected.")
				Log.d(tag, "Current bot running state: ${BotService.isRunning}")
				
				// Check if the bot is currently running.
				if (BotService.isRunning) {
					Log.d(tag, "Bot is running. Disabling gestures and interrupting bot thread due to device sleep.")
					
					// Disable gestures immediately to prevent any further dispatches.
					MyAccessibilityService.disableGestures()
					Log.d(tag, "Gestures disabled. isGestureAllowed = ${MyAccessibilityService.isGestureAllowed}")
					
					// Interrupt the bot thread to stop execution.
					BotService.interruptBotThread()
					Log.d(tag, "Bot thread interrupted.")
					
					// Log the reason for stopping.
					MessageLog.i(tag, "Bot stopped: Device went to sleep.")
					
					// Update notification with the reason.
					val contentIntent: Intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
					val className = contentIntent.component!!.className
					NotificationUtils.updateNotification(context, Class.forName(className), false, "Bot stopped: Device went to sleep.")
				} else {
					Log.d(tag, "Bot is not running. No action needed.")
				}
			}
			
			Intent.ACTION_SCREEN_ON -> {
				Log.d(tag, "Screen turned on detected.")
			}
		}
	}
}

