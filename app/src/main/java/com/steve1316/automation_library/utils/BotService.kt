package com.steve1316.automation_library.utils

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.steve1316.automation_library.R
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.events.ExceptionEvent
import com.steve1316.automation_library.events.JSEvent
import com.steve1316.automation_library.events.StartEvent
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import kotlin.concurrent.thread
import kotlin.math.roundToInt

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
	private lateinit var overlayView: View
	private lateinit var overlayButton: ImageButton
	private var isException: Boolean = false

	companion object {
		private lateinit var thread: Thread
		private lateinit var windowManager: WindowManager

		// Create the LayoutParams for the floating overlay START/STOP button.
		private val overlayLayoutParams = WindowManager.LayoutParams().apply {
			type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
			} else {
				WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
			}
			flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
			format = PixelFormat.TRANSLUCENT
			width = WindowManager.LayoutParams.WRAP_CONTENT
			height = WindowManager.LayoutParams.WRAP_CONTENT
			windowAnimations = android.R.style.Animation_Toast
		}

		var isRunning = false
	}

	@SuppressLint("ClickableViewAccessibility", "InflateParams")
	override fun onCreate() {
		super.onCreate()
		EventBus.getDefault().register(this)

		// Save a reference to the app's context and app name.
		myContext = this
		appName = myContext.getString(R.string.app_name)

		// Display the overlay view layout on the screen.
		overlayView = LayoutInflater.from(this).inflate(R.layout.bot_actions, null)
		windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
		windowManager.addView(overlayView, overlayLayoutParams)

		// This button is able to be moved around the screen. Attach a onTouchListener for starting / stopping the entry point back in the developer's module.
		overlayButton = overlayView.findViewById(R.id.bot_actions_overlay_button)
		overlayButton.setOnTouchListener(object : View.OnTouchListener {
			private var initialX: Int = 0
			private var initialY: Int = 0
			private var initialTouchX: Float = 0F
			private var initialTouchY: Float = 0F

			override fun onTouch(v: View?, event: MotionEvent?): Boolean {
				val action = event?.action

				if (action == MotionEvent.ACTION_DOWN) {
					// Sets up the coordinates in preparation for the user moving the overlay button around via MotionEvent.ACTION_MOVE.
					// Get the initial position.
					initialX = overlayLayoutParams.x
					initialY = overlayLayoutParams.y

					// Now get the new position.
					initialTouchX = event.rawX
					initialTouchY = event.rawY

					return false
				} else if (action == MotionEvent.ACTION_UP) {
					val elapsedTime: Long = event.eventTime - event.downTime
					if (elapsedTime < 100L) {
						if (!isRunning) {
							Log.d(tag, "BotService for $appName is now running.")
							Toast.makeText(myContext, "BotService for $appName is now running.", Toast.LENGTH_SHORT).show()
							overlayButton.setImageResource(R.drawable.stop_circle_filled)
							isRunning = true

							// Get the developer module's MainActivity class.
							val contentIntent: Intent = packageManager.getLaunchIntentForPackage(packageName)!!
							val className = contentIntent.component!!.className

							// Set up the notification to send the user back to their MainActivity when pressed.
							NotificationUtils.updateNotification(myContext, Class.forName(className), "Automation is now running")

							thread = thread {
								// Clear the message log in the frontend.
								EventBus.getDefault().post(JSEvent("BotService", "Running"))

								// Run the Discord process on a new Thread if it is enabled.
								if (PreferenceManager.getDefaultSharedPreferences(myContext).getBoolean("enableDiscordNotifications", false)) {
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

								// Perform cleanup after everything is done.
								if (!isException) performCleanUp()
								else isException = false
							}
						} else {
							// If the entry point was already in the middle of running, stop it and perform cleanup.
							thread.interrupt()
							performCleanUp()
						}

						// Returning true here freezes the animation of the click on the button.
						return false
					}
				} else if (action == MotionEvent.ACTION_MOVE) {
					val xDiff = (event.rawX - initialTouchX).roundToInt()
					val yDiff = (event.rawY - initialTouchY).roundToInt()

					// Calculate the X and Y coordinates of the view.
					overlayLayoutParams.x = initialX + xDiff
					overlayLayoutParams.y = initialY + yDiff

					// Now update the layout.
					windowManager.updateViewLayout(overlayView, overlayLayoutParams)
					return false
				}

				return false
			}
		})
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

		// Remove the overlay View that holds the overlay button.
		windowManager.removeView(overlayView)

		// Stop the Accessibility service.
		val service = Intent(myContext, MyAccessibilityService::class.java)
		myContext.stopService(service)
	}

	/**
	 * Perform cleanup upon app completion or encountering an Exception.
	 *
	 */
	private fun performCleanUp() {
		Log.d(tag, "BotService for $appName is now stopped.")
		isRunning = false

		DiscordUtils.queue.add("```diff\n- Terminated connection to Discord API for $appName\n```")

		// Save the message log and reset MessageLog.
		MessageLog.saveLogToFile(myContext)
		MessageLog.reset()

		// Update the app's notification with the status.
		if (!isException) {
			val contentIntent: Intent = packageManager.getLaunchIntentForPackage(packageName)!!
			val className = contentIntent.component!!.className
			NotificationUtils.updateNotification(myContext, Class.forName(className), "Completed successfully with no errors.")
		}

		// Reset the overlay button's image on a separate UI thread.
		Handler(Looper.getMainLooper()).post {
			overlayButton.setImageResource(R.drawable.play_circle_filled)
		}
	}

	/**
	 * Listener function to call the inner event sending function in order to send the message back to the Javascript frontend.
	 *
	 * @param event The JSEvent object to parse its event name and message.
	 */
	@Subscribe
	fun onExceptionEvent(event: ExceptionEvent) {
		// Get the developer module's MainActivity class.
		val contentIntent: Intent = packageManager.getLaunchIntentForPackage(packageName)!!
		val className = contentIntent.component!!.className

		if (event.exception.toString() == "java.lang.InterruptedException") {
			NotificationUtils.updateNotification(myContext, Class.forName(className), "Completed successfully with no errors.")
		} else {
			NotificationUtils.updateNotification(
				myContext,
				Class.forName(className),
				"${event.exception.javaClass.canonicalName}\nTap me to see more details.",
				title = "Encountered Exception",
				displayBigText = true
			)

			MessageLog.printToLog("$appName encountered an Exception: ${event.exception.stackTraceToString()}", tag, isError = true)

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
		}

		// Perform cleanup after everything is done but mark the overall result as a failure.
		isException = true
		performCleanUp()
	}
}