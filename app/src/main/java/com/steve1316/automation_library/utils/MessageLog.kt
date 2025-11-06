package com.steve1316.automation_library.utils

import android.annotation.SuppressLint
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

enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/**
 * This class is in charge of holding the Message Log to which all logging messages from the bot goes to and also saves it all into a file when the bot has finished.
 */
class MessageLog {
	companion object {
		private const val TAG: String = "${SharedData.loggerTag}MessageLog"

		private var messageLog = arrayListOf<String>()
        private var startTimeMs: Long = 0L
        private var saveCheck: Boolean = false

		// Add synchronization object for thread-safe access
		private val messageLogLock = Object()

        /** Resets state to prepare for the next run. */
        fun reset() {
            startTimeMs = 0L
            saveCheck = false
            clearLog()
            Log.d(TAG, "MessageLog has now been reset and is ready for the next run.")
        }

		/**
		 * Save the current Message Log into a new file inside internal storage's /logs/ folder.
		 *
		 * @param context The context for the application.
		 */
		fun saveLogToFile(context: Context) {
			if (!saveCheck) {
                cleanLogsFolder(context)

				Log.d(TAG, "Now beginning process to save current Message Log to internal storage...")

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
                val logString: String = "Now saving Message Log to file named \"$fileName\" at $path"
				Log.d(TAG, logString)
                messageLog.add("\n$logString")
                EventBus.getDefault().post(JSEvent("MessageLog", "\n$logString"))

				val file = File(path, "$fileName.txt")

				if (!file.exists()) {
					file.createNewFile()
					file.printWriter().use { out ->
						// Synchronize access to messageLog to prevent concurrent modification
						synchronized(messageLogLock) {
							messageLog.forEach {
								out.println(it)
							}
						}
					}
					saveCheck = true
				}
			}
		}

		/**
		 * Add a message to the log in a thread-safe manner.
		 *
		 * @param message The message to add to the log.
		 */
		fun addMessage(message: String) {
			synchronized(messageLogLock) {
				messageLog.add(message)
			}
		}

		/**
		 * Clear the message log in a thread-safe manner.
		 */
		fun clearLog() {
			synchronized(messageLogLock) {
				messageLog.clear()
			}
		}

		/**
		 * Get a copy of the current message log in a thread-safe manner.
		 *
		 * @return A copy of the current message log.
		 */
		fun getMessageLogCopy(): List<String> {
			synchronized(messageLogLock) {
				return ArrayList(messageLog)
			}
		}

		/**
		 * Clean up the logs folder if the amount of logs inside is greater than the specified amount.
		 *
		 * @param context The context for the application.
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
        * @return String of HH:MM:SS format of the elapsed time.
        */
        @SuppressLint("DefaultLocale")
        fun getElapsedTimeString(): String {
            if (startTimeMs == 0L) {
                startTimeMs = System.currentTimeMillis()
            }
            val elapsedMillis: Long = System.currentTimeMillis() - startTimeMs

            val hours = TimeUnit.MILLISECONDS.toHours(elapsedMillis)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMillis) - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(elapsedMillis))
            val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMillis) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(elapsedMillis))
            val milliseconds = elapsedMillis % 1000

            return String.format(
                "%02d:%02d:%02d.%03d",
                hours,
                minutes,
                seconds,
                milliseconds,
            )
        }

        /**
        * Returns a formatted string of the current system time as HH:MM:SS format.
        *
        * Source is from https://stackoverflow.com/questions/9027317/how-to-convert-milliseconds-to-hhmmss-format/9027379
        *
        * @return String of HH:MM:SS formatted time.
        */
        @SuppressLint("DefaultLocale")
        fun getSystemTimeString(): String {
            val timeMs: Long = System.currentTimeMillis()

            val hours = TimeUnit.MILLISECONDS.toHours(timeMs)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(timeMs) - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(timeMs))
            val seconds = TimeUnit.MILLISECONDS.toSeconds(timeMs) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(timeMs))
            val milliseconds = timeMs % 1000

            return String.format(
                "%02d:%02d:%02d.%03d",
                hours,
                minutes,
                seconds,
                milliseconds,
            )
        }

		/**
		 * Print the specified message to debug console and then saves the message to the log.
		 *
         * @param tag Distinguishes between messages for where they came from. Defaults to Game's TAG.
		 * @param message Message to be saved.
         * @param level The log level of the message. String added to beginning of message in brackets.
         * @param skipPrintTime Flag to suppress adding the timestamp to the logged message.
		 */
		fun log(
            tag: String = TAG,
            message: String,
            level: LogLevel = LogLevel.DEBUG,
            skipPrintTime: Boolean = false,
        ) {
            when (level) {
                LogLevel.VERBOSE -> Log.v(tag, message)
                LogLevel.DEBUG -> Log.d(tag, message)
                LogLevel.INFO -> Log.i(tag, message)
                LogLevel.WARN -> Log.w(tag, message)
                LogLevel.ERROR -> Log.e(tag, message)
            }

            var prefix = ""
            if (!skipPrintTime) {
                prefix += "${getElapsedTimeString()} "
            }

            prefix += "[${level.name}]"

            val msg = if (message.startsWith("\n")) {
                "\n$prefix " + message.removePrefix("\n")
            } else {
                "$prefix $message"
            }

            messageLog.add(msg)

            // Send the message to the frontend.
			EventBus.getDefault().post(JSEvent("MessageLog", msg))
		}

        // Wrappers around the log() function.
        fun v(tag: String = TAG, message: String, skipPrintTime: Boolean = false) {
            log(tag, message, LogLevel.VERBOSE, skipPrintTime)
        }

        fun d(tag: String = TAG, message: String, skipPrintTime: Boolean = false) {
            log(tag, message, LogLevel.DEBUG, skipPrintTime)
        }

        fun i(tag: String = TAG, message: String, skipPrintTime: Boolean = false) {
            log(tag, message, LogLevel.INFO, skipPrintTime)
        }

        fun w(tag: String = TAG, message: String, skipPrintTime: Boolean = false) {
            log(tag, message, LogLevel.WARN, skipPrintTime)
        }

        fun e(tag: String = TAG, message: String, skipPrintTime: Boolean = false) {
            log(tag, message, LogLevel.ERROR, skipPrintTime)
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
            Log.w(tag, "[DEPRECATED] The printToLog function is deprecated and needs to be replaced.")

			if (!isError && isWarning) {
				w(tag, message)
			} else if (isError && !isWarning) {
				e(tag, message)
			} else {
				d(tag, message)
			}
		}
	}
}
