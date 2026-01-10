package com.steve1316.automation_library.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Utility class for managing battery optimization settings.
 *
 * This helps ensure the app can run reliably in the background by prompting
 * users to disable battery optimization restrictions.
 */
object BatteryOptimizationUtils {

	/**
	 * Checks if battery optimization is disabled for this app.
	 *
	 * @param context The application context.
	 * @return True if the app is ignoring battery optimizations, false otherwise.
	 */
	fun isIgnoringBatteryOptimizations(context: Context): Boolean {
		val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
		return powerManager.isIgnoringBatteryOptimizations(context.packageName)
	}

	/**
	 * Opens the battery optimization settings dialog for this app.
	 * This prompts the user to allow the app to run without restrictions.
	 *
	 * Note: This requires the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission
	 * in the consuming app's AndroidManifest.xml.
	 *
	 * @param context The application context.
	 */
	fun requestIgnoreBatteryOptimizations(context: Context) {
		val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
			data = Uri.parse("package:${context.packageName}")
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		context.startActivity(intent)
	}

	/**
	 * Opens the general battery optimization settings page.
	 * Use this as a fallback if the direct request intent doesn't work on certain devices.
	 *
	 * @param context The application context.
	 */
	fun openBatteryOptimizationSettings(context: Context) {
		val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		context.startActivity(intent)
	}

	/**
	 * Checks if battery optimization is enabled and prompts the user to disable it.
	 * This is a convenience method that combines the check and request.
	 *
	 * @param context The application context.
	 * @return True if the app was already ignoring battery optimizations, false if a request was made.
	 */
	fun checkAndRequestBatteryOptimization(context: Context): Boolean {
		return if (isIgnoringBatteryOptimizations(context)) {
			true
		} else {
			requestIgnoreBatteryOptimizations(context)
			false
		}
	}
}
