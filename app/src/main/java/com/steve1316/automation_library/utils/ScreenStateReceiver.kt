package com.steve1316.automation_library.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.steve1316.automation_library.data.SharedData

/**
 * BroadcastReceiver to detect when the device screen turns on or off.
 * This will gracefully stop the bot when the device goes to sleep to prevent the bot from continuing to execute gestures on a sleeping device.
 */
class ScreenStateReceiver : BroadcastReceiver() {
	private val tag: String = "${SharedData.loggerTag}ScreenStateReceiver"

	override fun onReceive(context: Context, intent: Intent?) {
		when (intent?.action) {
			Intent.ACTION_SCREEN_OFF -> {
				Log.d(tag, "Screen turned off detected.")
				
				// Check if the bot is currently running.
				if (BotService.isRunning) {
					Log.d(tag, "Bot is running. Interrupting bot thread due to device sleep.")
					
					// Interrupt the bot thread to stop execution.
					BotService.interruptBotThread()
					
					// Log the reason for stopping.
					MessageLog.printToLog("Bot stopped: Device went to sleep.", tag)
					
					// Update notification with the reason.
					val contentIntent: Intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
					val className = contentIntent.component!!.className
					NotificationUtils.updateNotification(context, Class.forName(className), false, "Bot stopped: Device went to sleep.")
				}
			}
			
			Intent.ACTION_SCREEN_ON -> {
				Log.d(tag, "Screen turned on detected.")
			}
		}
	}
}

