package com.steve1316.automation_library.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.events.JSEvent
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.Comparator

/**
 * This class is in charge of holding the Message Log to which all logging messages from the bot goes to and also saves it all into a file when the app has finished what it was doing.
 *
 */
class MessageLog {
	companion object {
		private const val tag: String = "${SharedData.loggerTag}MessageLog"

		var messageLog = arrayListOf<String>()
		private val startTime: Long = System.currentTimeMillis()
		var saveCheck = false

		/**
		 * Save the current Message Log into a new file inside internal storage's /logs/ folder.
		 *
		 * @param context The context for the application.
		 */
		fun saveLogToFile(context: Context) {
			cleanLogsFolder(context)

			if (!saveCheck) {
				Log.d(tag, "Now beginning process to save current Message Log to internal storage...")

				// Generate file path to save to. All message logs will be saved to the /logs/ folder inside internal storage. Create the /logs/ folder if needed.
				val path = File(context.getExternalFilesDir(null)?.absolutePath + "/logs/")
				if (!path.exists()) {
					path.mkdirs()
				}

				// Generate the file name.
				val fileName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					val current = LocalDateTime.now()
					val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH_mm_ss")
					"log @ ${current.format(formatter)}"
				} else {
					val current = SimpleDateFormat("HH_mm_ss", Locale.getDefault()).format(Date())
					val sdf = SimpleDateFormat("yyyy-MM-dd HH_mm_ss", Locale.getDefault())
					"log @ ${current.format(sdf)}"
				}

				// Now save the Message Log to the new text file.
				Log.d(tag, "Now saving Message Log to file named \"$fileName\" at $path")
				messageLog.add("\nNow saving Message Log to file named \"$fileName\" at $path")

				// Send a event to the React Native frontend.
				EventBus.getDefault().post(JSEvent("MessageLog", "Now saving Message Log to file named \"$fileName\" at $path"))

				val file = File(path, "$fileName.txt")

				if (!file.exists()) {
					file.createNewFile()
					file.printWriter().use { out ->
						messageLog.forEach {
							out.println(it)
						}
					}
				}

				saveCheck = true
			}
		}

		/**
		 * Clean up the logs folder if the amount of logs inside is greater than the specified amount.
		 *
		 * @param context The context for the application.
		 * @param maxAmount If the amount of files is greater than this value, then delete all logs. Defaults to 50.
		 */
		private fun cleanLogsFolder(context: Context, maxAmount: Int = 50) {
			val directory = File(context.getExternalFilesDir(null)?.absolutePath + "/logs/")

			// Delete the oldest logs if the amount inside is greater than the given max amount.
			val files = directory.listFiles()
			if (files != null) {
				Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed())
				val diff = files.size - maxAmount
				if (diff > 0) {
					for (i in 0..diff) {
						files[i].delete()
					}
				}
			}
		}

		/**
		 * Returns a formatted string of the elapsed time since the bot started as HH:MM:SS format.
		 *
		 * Source is from https://stackoverflow.com/questions/9027317/how-to-convert-milliseconds-to-hhmmss-format/9027379
		 *
		 * @param skipPrintTime Flag to determine printing the timestamp.
		 * @return String of HH:MM:SS format of the elapsed time.
		 */
		private fun printTime(skipPrintTime: Boolean = false): String {
			val elapsedMillis: Long = System.currentTimeMillis() - startTime

			return if (!skipPrintTime) {
				String.format(
					"%02d:%02d:%02d",
					TimeUnit.MILLISECONDS.toHours(elapsedMillis),
					TimeUnit.MILLISECONDS.toMinutes(elapsedMillis) - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(elapsedMillis)),
					TimeUnit.MILLISECONDS.toSeconds(elapsedMillis) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(elapsedMillis))
				)
			} else {
				""
			}
		}

		/**
		 * Print the specified message to debug console and then saves the message to the log.
		 *
		 * @param message Message to be saved.
		 * @param tag Distinguish between messages for where they came from.
		 * @param isWarning Flag to determine whether to display log message in console as debug or warning.
		 * @param isError Flag to determine whether to display log message in console as debug or error.
		 * @param skipPrintTime Flag to determine printing the timestamp in the message.
		 */
		fun printToLog(message: String, tag: String, isWarning: Boolean = false, isError: Boolean = false, skipPrintTime: Boolean = false) {
			if (!isError && isWarning) {
				Log.w(tag, message)
			} else if (isError && !isWarning) {
				Log.e(tag, message)
			} else {
				Log.d(tag, message)
			}

			var timestamp: String = printTime(skipPrintTime)
			if (timestamp != "") timestamp = "$timestamp "

			// Remove the newline prefix if needed and place it where it should be.
			val newMessage = if (message.startsWith("\n")) {
				"\n" + timestamp + message.removePrefix("\n")
			} else {
				timestamp + message
			}

			messageLog.add(newMessage)

			// Send the message to the frontend.
			EventBus.getDefault().post(JSEvent("MessageLog", newMessage))
		}
	}
}