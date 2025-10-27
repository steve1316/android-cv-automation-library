package com.steve1316.automation_library.data

import android.content.Context
import android.content.pm.PackageManager

/**
 * Contains various shared variables to be used across a variety of objects, including from the developer's module.
 *
 */
class SharedData {
	companion object {
		const val loggerTag = "[AUTO_LIB]"

		var templateSubfolderPathName = "/"
		var templateImageExt = "png"

		// These are set in the MediaProjectionService upon successful creation of the Virtual Display.
		var mainPackagePath: String = ""
		var displayWidth: Int = 0
		var displayHeight: Int = 0
		var displayDPI: Int = 0

		/**
		 * Get the version name of this library.
		 *
		 * @param context The application context.
		 * @return The version name string.
		 */
		fun getVersionName(context: Context): String {
			return try {
				val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
				packageInfo.versionName ?: "Unknown"
			} catch (_: PackageManager.NameNotFoundException) {
				"Unknown"
			}
		}
	}
}