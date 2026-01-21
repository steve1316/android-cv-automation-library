package com.steve1316.automation_library.utils

import android.util.Log
import com.steve1316.automation_library.data.SharedData
import kotlin.system.exitProcess

/**
 * Custom exception handler that catches any uncaught exceptions in the backend and logs them to the MessageLog.
 * This ensures that even if the app crashes, the reason is recorded in the log file.
 *
 * @property defaultHandler The original default exception handler to delegate to if needed.
 */
class GlobalExceptionHandler(private val defaultHandler: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
	private val tag: String = "${SharedData.loggerTag}GlobalExceptionHandler"

	override fun uncaughtException(thread: Thread, throwable: Throwable) {
		try {
			// Log the exception to the MessageLog.
			MessageLog.e(tag, "Uncaught exception in thread ${thread.name}: ${throwable.stackTraceToString()}")

			// Post an ExceptionEvent via EventBus to trigger a graceful cleanup in BotService.
			// This prevents the backend from "crashing entirely" and ensures logs are saved.
			org.greenrobot.eventbus.EventBus.getDefault().post(com.steve1316.automation_library.events.ExceptionEvent(throwable))
		} catch (e: Exception) {
			// If logging itself fails, just log to logcat.
			Log.e(tag, "Error during uncaught exception handling", e)

			// If EventBus fails, we should let the default handler take over.
			defaultHandler?.uncaughtException(thread, throwable)
		}
	}

	companion object {
		/**
		 * Registers the GlobalExceptionHandler for the current thread.
		 * 
		 */
		fun register() {
			Thread.setDefaultUncaughtExceptionHandler(
				GlobalExceptionHandler(Thread.getDefaultUncaughtExceptionHandler())
			)
		}
	}
}
