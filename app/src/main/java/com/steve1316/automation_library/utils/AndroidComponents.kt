package com.steve1316.automation_library.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Shared Android components file that holds custom implementations of Android elements.
 */
object AndroidComponents {
	/**
	 * Displays a Toast with a custom duration.
	 *
	 * @param context The Context to use for the Toast.
	 * @param text The text to display in the Toast.
	 * @param durationMs The duration in milliseconds before the Toast is cancelled.
	 */
	fun showCustomToast(context: Context, text: CharSequence, durationMs: Long) {
		// Create the Toast with LENGTH_SHORT as the base duration.
		val toast = Toast.makeText(context, text, Toast.LENGTH_SHORT)
		toast.show()

		// Use a Handler to cancel the Toast after the specified duration.
		Handler(Looper.getMainLooper()).postDelayed({
			toast.cancel()
		}, durationMs)
	}
}
