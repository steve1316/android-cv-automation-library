package com.steve1316.automation_library.utils

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.steve1316.automation_library.BuildConfig
import com.steve1316.automation_library.R
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.events.ExceptionEvent
import com.steve1316.automation_library.events.JSEvent
import com.steve1316.automation_library.events.StartEvent
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import kotlin.concurrent.thread
import java.io.File

/**
 * This Service will allow starting and stopping the automation workflow on a Thread based on the chosen preference settings.
 *
 * Source for being able to send custom Intents to BroadcastReceiver to notify users of app state changes is from:
 * https://www.tutorialspoint.com/in-android-how-to-register-a-custom-intent-filter-to-a-broadcast-receiver
 */
class BotService : Service() {
	private val tag: String = "${SharedData.loggerTag}BotService"
	private var appName: String = ""
	private lateinit var myContext: Context
	private var isException: Boolean = false
	private var skipNotificationUpdate: Boolean = false

	private lateinit var floatingOverlayButton: FloatingOverlayButton

	companion object {
		private lateinit var thread: Thread
		private lateinit var windowManager: WindowManager

		var isRunning = false

		/**
		 * Interrupt the bot thread if it's running. Used by ScreenStateReceiver when device goes to sleep.
		 * Note: Gestures should be disabled BEFORE calling this method.
		 */
		fun interruptBotThread() {
			// Interrupt the thread.
			if (::thread.isInitialized) {
				thread.interrupt()
			}
		}

		private fun isBotThreadInitialized(): Boolean {
			return ::thread.isInitialized
		}
	}

	@SuppressLint("ClickableViewAccessibility", "InflateParams")
	override fun onCreate() {
		super.onCreate()
		EventBus.getDefault().register(this)

		// Save a reference to the app's context and app name.
		myContext = this
		appName = myContext.getString(R.string.app_name)
		windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

		// Initialize the floating overlay button which handles all UI and animations.
		floatingOverlayButton = FloatingOverlayButton(this, windowManager)

		// Set up the listeners
		floatingOverlayButton.setOnClickListener {
			val contentIntent: Intent = packageManager.getLaunchIntentForPackage(packageName)!!
			val className = contentIntent.component!!.className

			if (!isRunning) {
				Log.d(tag, "BotService for $appName is now running.")
                Log.d(tag, "Automation Library version: ${BuildConfig.VERSION_NAME}")
				Toast.makeText(myContext, "BotService for $appName is now running.", Toast.LENGTH_SHORT).show()
				isRunning = true
				floatingOverlayButton.setRunningState(true)

				// Set up the notification to send the user back to their MainActivity when pressed.
				NotificationUtils.updateNotification(myContext, Class.forName(className), true, "Automation is now running")

				// Enable gestures when starting the bot.
				MyAccessibilityService.enableGestures()

				// Clear all contents from the /files/temp/ folder to start fresh.
				val externalFilesDir: File? = getExternalFilesDir(null)
				if (externalFilesDir != null) {
					val tempDirectory = myContext.getExternalFilesDir(null)?.absolutePath + "/temp/"
					val newTempDirectory = File(tempDirectory)
					if (newTempDirectory.exists()) {
						val files = newTempDirectory.listFiles()
						if (files != null) {
							var deletedCount = 0
							for (file in files) {
								if (file.delete()) {
									deletedCount++
								} else {
									Log.w(tag, "Failed to delete file: ${file.name}")
								}
							}
							if (deletedCount > 0) {
									Log.d(tag, "Cleared $deletedCount file(s) from /files/temp/ folder.")
							}
						}
					}
				}

				// Reset the save check flag and start the timer for the MessageLog.
				MessageLog.start()

				thread = thread {
					try {
						// Clear the message log in the frontend.
						EventBus.getDefault().post(JSEvent("BotService", "Running"))

						// Run the Discord process on a new Thread if it is enabled.
						if (DiscordUtils.enableDiscordNotifications) {
							val discordUtils = DiscordUtils(myContext)
							thread {
								runBlocking {
									DiscordUtils.queue.clear()
									discordUtils.main()
								}
							}
						}

						// Send start message to signal the developer's module to begin running their entry point. Execution will go to the developer's module until it is all done.
						EventBus.getDefault().postSticky(StartEvent("Entry Point ON"))
					} catch (e: Exception) {
						if (e.toString() == "java.lang.InterruptedException" || Thread.currentThread().isInterrupted) {
							if (e.message?.contains("crashed") == true || e.message?.contains("stopped unexpectedly") == true) {
								NotificationUtils.updateNotification(myContext, Class.forName(className), false, "Bot stopped: ${e.message}")
							} else {
								NotificationUtils.updateNotification(myContext, Class.forName(className), false, "Bot was manually stopped.")
							}
						} else {
							NotificationUtils.updateNotification(myContext, Class.forName(className), false, "Encountered an Exception: $e.\nTap me to see more details.")
							MessageLog.e(tag, "$appName encountered an Exception: ${e.stackTraceToString()}")
						}
					} finally {
                        Log.d(tag, "Performing cleanup in the finally block...")
						performCleanUp()
					}
				}
			} else {
				// If the entry point was already in the middle of running, stop it and perform cleanup.
				Log.d(tag, "Overlay button was pressed while process was running. Interrupting the process now...")
				thread.interrupt()
				NotificationUtils.updateNotification(myContext, Class.forName(className), false, "Bot was manually stopped.")
				performCleanUp()
			}
		}

		floatingOverlayButton.setOnDismissListener {
			dismissOverlayButton()
		}
	}

	/**
	 * Dismiss the overlay button and stop this service.
	 */
	private fun dismissOverlayButton() {
		if (::floatingOverlayButton.isInitialized) floatingOverlayButton.cleanup()

        if (isRunning && isBotThreadInitialized()) {
			Log.d(tag, "Interrupting the bot thread now from the dismiss overlay...")
			thread.interrupt()
			performCleanUp()
		}

		// Stop the MediaProjection service to fully tear down overlays.
		try {
			stopService(MediaProjectionService.getStopIntent(myContext))
		} catch (_: Exception) {
			Log.w(tag, "Failed to start MediaProjection stop intent.")
		}

		stopSelf()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		// Do not attempt to restart the service if it crashes.
		return START_NOT_STICKY
	}

	override fun onBind(intent: Intent?): IBinder? {
		return null
	}

	override fun onDestroy() {
		super.onDestroy()
		EventBus.getDefault().unregister(this)

		if (::floatingOverlayButton.isInitialized) floatingOverlayButton.cleanup()

		// Stop the Accessibility service.
		Log.d(tag, "BotService is now being destroyed. Shutting down the Accessibility Service as well.")
		val service = Intent(myContext, MyAccessibilityService::class.java)
		myContext.stopService(service)
	}

	/**
	 * Perform cleanup upon app completion or encountering an Exception.
	 *
	 */
	private fun performCleanUp() {
		if (!skipNotificationUpdate) {
			Log.d(tag, "BotService for $appName is now stopped and executing cleanup now...")
			isRunning = false

			DiscordUtils.queue.add("```diff\n- Terminated connection to Discord API for $appName\n```")

			// Save the message log and reset MessageLog.
			MessageLog.saveLogToFile(myContext)

			// Update the app's notification with the status.
			if (!isException) {
				val contentIntent: Intent = packageManager.getLaunchIntentForPackage(packageName)!!
				val className = contentIntent.component!!.className
                Log.d(tag, "Updating notification for completion success with no exception.")
				NotificationUtils.updateNotification(myContext, Class.forName(className), false, "Completed successfully with no errors.")
			} else {
				skipNotificationUpdate = true
			}

			// Reset the overlay button's image on a separate UI thread.
			Handler(Looper.getMainLooper()).post {
				if (::floatingOverlayButton.isInitialized) floatingOverlayButton.setRunningState(false)
			}

			isException = false
		} else {
			skipNotificationUpdate = false
		}

		// Reset the overlay button's image and animation on a separate UI thread.
		Handler(Looper.getMainLooper()).post {
			if (::floatingOverlayButton.isInitialized) floatingOverlayButton.setRunningState(false)
		}
	}

	/**
	 * Listener function to call the inner event sending function in order to send the message back to the Javascript frontend.
	 *
	 * @param event The JSEvent object to parse its event name and message.
	 */
	@Subscribe
	fun onExceptionEvent(event: ExceptionEvent) {
		Log.d(tag, "Now executing logic for the ExceptionEvent listener.")

		// Get the developer module's MainActivity class.
		val contentIntent: Intent = packageManager.getLaunchIntentForPackage(packageName)!!
		val className = contentIntent.component!!.className

		if (event.exception.toString() == "java.lang.InterruptedException") {
            Log.d(tag, "InterruptedException detected. Assuming process was manually stopped.")
			NotificationUtils.updateNotification(myContext, Class.forName(className), false, "Completed successfully with no errors.")
		} else {
			Log.d(tag, "Process has finished running but an exception(s) were detected.")

			NotificationUtils.updateNotification(
				myContext,
				Class.forName(className),
				false,
				"${event.exception.javaClass.canonicalName}\nTap me to see more details.",
				title = "Encountered Exception",
				displayBigText = true
			)

			MessageLog.e(tag, "$appName encountered an Exception: ${event.exception.stackTraceToString()}")

			if (event.exception.stackTraceToString().length >= 2500) {
				Log.d(tag, "Splitting up Discord message to avoid being cut off due to character limit.")
				val halfLength: Int = event.exception.stackTraceToString().length / 2
				val message1: String = event.exception.stackTraceToString().substring(0, halfLength)
				val message2: String = event.exception.stackTraceToString().substring(halfLength)

				DiscordUtils.queue.add("> Encountered exception: \n$message1")
				DiscordUtils.queue.add("> $message2")
			} else {
				DiscordUtils.queue.add("> Encountered exception: \n${event.exception.stackTraceToString()}")
			}

			isException = true
			performCleanUp()
		}
	}
}