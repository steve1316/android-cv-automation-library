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
import com.steve1316.automation_library.BuildConfig
import com.steve1316.automation_library.R
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.events.ExceptionEvent
import com.steve1316.automation_library.events.JSEvent
import com.steve1316.automation_library.events.StartEvent
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import java.io.File
import kotlin.concurrent.thread

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

    // Set in onDestroy() so a run that is still unwinding does not post a notification or touch the overlay after shutdown.
    @Volatile
    private var isDestroyed: Boolean = false

    private lateinit var floatingOverlayButton: FloatingOverlayButton

    companion object {
        private lateinit var thread: Thread
        private lateinit var windowManager: WindowManager

        // Read from the bot thread and written from the UI thread, so it must be volatile.
        @Volatile
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

        // Register the Global Exception Handler to catch any uncaught exceptions and log them.
        GlobalExceptionHandler.register()

        EventBus.getDefault().register(this)

        // Save a reference to the app's context and app name.
        myContext = this
        appName = myContext.getString(R.string.app_name)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Initialize SettingsHelper for SQLite settings access.
        SettingsHelper.initialize(myContext)

        // Initialize the floating overlay button which handles all UI and animations.
        floatingOverlayButton = FloatingOverlayButton(this, windowManager)

        // Set up the listeners
        floatingOverlayButton.setOnClickListener {
            if (!isRunning) startBot() else stopBot()
        }

        floatingOverlayButton.setOnDismissListener {
            dismissOverlayButton()
        }
    }

    /**
     * Starts a run on a new bot thread.
     */
    private fun startBot() {
        // The last run's thread can still be unwinding after a stop. A new run started now would have its state reset by that thread's cleanup.
        if (isBotThreadInitialized() && thread.isAlive) {
            Log.d(tag, "Not starting a new run because the last run's thread is still stopping.")
            AndroidComponents.showCustomToast(myContext, "Still stopping the last run. Try again in a moment.", 1500)
            return
        }

        Log.d(tag, "BotService for $appName is now running.")
        Log.d(tag, "Automation Library version: ${BuildConfig.VERSION_NAME}")

        // Display a custom Toast for 1 second (1000ms) to notify the user.
        AndroidComponents.showCustomToast(myContext, "BotService for $appName is now running.", 1000)

        DiscordUtils.enableDiscordNotifications = SettingsHelper.getBooleanSetting("discord", "enableDiscordNotifications", false)
        MessageLog.debugMode = SettingsHelper.getBooleanSetting("debug", "enableDebugMode", false)

        // Clear the previous run's status and any leftover pause before the overlay and the notification read them.
        BotStatus.reset()
        BotHold.reset()

        isRunning = true
        floatingOverlayButton.setRunningState(true)

        // Follow the run's status in the notification until it ends.
        NotificationUtils.startRunUpdates(myContext, getLaunchActivityClass())

        // Enable gestures when starting the bot.
        MyAccessibilityService.enableGestures()

        // Clear all contents from the bot's internal temp folder to start fresh.
        val tempDirectory = File(myContext.filesDir, "temp")
        if (tempDirectory.exists()) {
            val files = tempDirectory.listFiles()
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
                    Log.d(tag, "Cleared $deletedCount file(s) from internal temp folder.")
                }
            }
        }

        // Reset the save check flag and start the timer for the MessageLog.
        MessageLog.start()

        thread =
            thread {
                try {
                    // Clear the message log in the frontend.
                    EventBus.getDefault().post(JSEvent("BotService", "Running"))

                    // Start screen recording if enabled in settings.
                    if (SharedData.enableScreenRecording) {
                        MediaProjectionService.startRecording(myContext)
                    }

                    // Run the Discord process on a new Thread if it is enabled.
                    if (DiscordUtils.enableDiscordNotifications) {
                        val discordUtils = DiscordUtils(myContext)
                        thread {
                            runBlocking {
                                DiscordUtils.queue.clear()
                                DiscordUtils.isRunning = true
                                discordUtils.main()
                            }
                        }
                    }

                    // Send start message to signal the developer's module to begin running their entry point. Execution will go to the developer's module until it is all done.
                    EventBus.getDefault().postSticky(StartEvent("Entry Point ON"))
                } catch (e: Exception) {
                    // The first outcome of a run wins, so a stop that set its own reason first, such as the device going to sleep, keeps it.
                    if (e.toString() == "java.lang.InterruptedException" || Thread.currentThread().isInterrupted) {
                        if (e.message?.contains("crashed") == true || e.message?.contains("stopped unexpectedly") == true) {
                            BotStatus.setOutcome(BotStatus.Outcome.STOPPED_BY_BOT, e.message ?: "Bot stopped")
                        } else {
                            BotStatus.setOutcome(BotStatus.Outcome.STOPPED_BY_USER, "You stopped the bot")
                        }
                    } else {
                        BotStatus.setOutcome(BotStatus.Outcome.CRASHED, e.javaClass.simpleName)
                        MessageLog.e(tag, "$appName encountered an Exception: ${e.stackTraceToString()}")
                    }
                } finally {
                    Log.d(tag, "Performing cleanup in the finally block...")
                    performCleanUp()
                }
            }
    }

    /**
     * Stops the run in progress because the user asked to.
     */
    private fun stopBot() {
        if (!isRunning || !isBotThreadInitialized()) return
        Log.d(tag, "Overlay button was pressed while process was running. Interrupting the process now...")
        // Set the outcome before interrupting, since the first outcome of a run wins.
        BotStatus.setOutcome(BotStatus.Outcome.STOPPED_BY_USER, "You stopped the bot")
        thread.interrupt()
        performCleanUp()
    }

    /**
     * Finds the consuming app's launch Activity, which the notification opens when tapped.
     *
     * @return The launch Activity's class.
     */
    private fun getLaunchActivityClass(): Class<*> = Class.forName(packageManager.getLaunchIntentForPackage(packageName)!!.component!!.className)

    /**
     * Dismiss the overlay button and stop this service.
     */
    private fun dismissOverlayButton() {
        if (::floatingOverlayButton.isInitialized) floatingOverlayButton.cleanup()

        if (isRunning && isBotThreadInitialized()) {
            Log.d(tag, "Interrupting the bot thread now from the dismiss overlay...")
            BotStatus.setOutcome(BotStatus.Outcome.STOPPED_BY_USER, "You stopped the bot")
            thread.interrupt()
            performCleanUp()
        }

        // Stop the MediaProjection service to fully tear down overlays.
        try {
            stopService(MediaProjectionService.getStopIntent(myContext))
        } catch (_: Exception) {
            Log.w(tag, "Failed to start MediaProjection stop intent.")
        }

        // Verify MediaProjection service is stopped and notification is dismissed.
        // Use a handler to check after a short delay to allow the stop to process.
        Handler(Looper.getMainLooper()).postDelayed({
            verifyServiceAndNotificationStopped()
        }, 500)

        stopSelf()
    }

    /**
     * Verifies that the MediaProjection service is stopped and the notification is dismissed.
     * If not, retries to stop them.
     */
    private fun verifyServiceAndNotificationStopped() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        val activeNotifications = notificationManager.activeNotifications

        Log.d(tag, "Verifying cleanup: MediaProjection.isRunning=${MediaProjectionService.isRunning}, activeNotifications=${activeNotifications.size}")

        // Check if MediaProjection service is still running or if there are still active notifications.
        if (MediaProjectionService.isRunning || activeNotifications.isNotEmpty()) {
            Log.w(tag, "MediaProjection service or notification still active. Forcing cleanup...")

            // Force cancel all notifications.
            notificationManager.cancelAll()
            Log.d(tag, "Cancelled all notifications via NotificationManager.")

            // Try to stop the MediaProjection service again.
            if (MediaProjectionService.isRunning) {
                try {
                    stopService(Intent(myContext, MediaProjectionService::class.java))
                    Log.d(tag, "Sent additional stop request to MediaProjectionService.")
                } catch (e: Exception) {
                    Log.e(tag, "Failed to stop MediaProjectionService: ${e.message}")
                }
            }

            // Check again after another delay.
            Handler(Looper.getMainLooper()).postDelayed({
                val finalNotifications = notificationManager.activeNotifications
                Log.d(tag, "Final verification: MediaProjection.isRunning=${MediaProjectionService.isRunning}, activeNotifications=${finalNotifications.size}")
                if (finalNotifications.isNotEmpty()) {
                    Log.w(tag, "Notifications still present after retry. Forcing cancelAll again.")
                    notificationManager.cancelAll()
                }
            }, 300)
        } else {
            Log.d(tag, "MediaProjection service and notification successfully stopped.")
        }
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
        isDestroyed = true
        EventBus.getDefault().unregister(this)

        if (::floatingOverlayButton.isInitialized) floatingOverlayButton.cleanup()

        // A run must not outlive this service. Otherwise the bot thread keeps tapping with no overlay or notification left to stop it.
        if (isRunning) {
            Log.d(tag, "BotService is being destroyed in the middle of a run. Stopping the run now.")
            // This is the notification's Stop path, so the user asked for it. Set it before interrupting, since the first outcome of a run wins.
            BotStatus.setOutcome(BotStatus.Outcome.STOPPED_BY_USER, "You stopped the bot")
            MyAccessibilityService.disableGestures()
            interruptBotThread()
            performCleanUp()
        }

        // Stop the Accessibility service.
        Log.d(tag, "BotService is now being destroyed. Shutting down the Accessibility Service as well.")
        val service = Intent(myContext, MyAccessibilityService::class.java)
        myContext.stopService(service)
    }

    /**
     * Perform cleanup upon app completion or encountering an Exception.
     *
     * The exception path and the destroy path call this before the bot thread's own `finally` does, so the flag skips that second pass.
     */
    private fun performCleanUp() {
        // Stop any active recording first to ensure proper file finalization.
        MediaProjectionService.stopRecording()

        // Stop the Discord message loop.
        DiscordUtils.isRunning = false

        if (!skipNotificationUpdate) {
            Log.d(tag, "BotService for $appName is now stopped and executing cleanup now...")
            isRunning = false

            // Save the message log and reset MessageLog.
            MessageLog.saveLogToFile(myContext)

            // A run that ended without anyone setting an outcome finished on its own.
            BotStatus.setOutcome(BotStatus.Outcome.FINISHED, "Run ended")
            BotHold.reset()
            NotificationUtils.stopRunUpdates()

            // After shutdown the notification has already been cancelled, so do not post it again.
            if (!isDestroyed) {
                NotificationUtils.postRunEnded(myContext, getLaunchActivityClass())
            }
            if (isException || isDestroyed) {
                skipNotificationUpdate = true
            }

            isException = false
        } else {
            skipNotificationUpdate = false
        }

        // Reset the overlay button's state on the UI thread.
        Handler(Looper.getMainLooper()).post {
            if (!isDestroyed && ::floatingOverlayButton.isInitialized) floatingOverlayButton.setRunningState(false)
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

        if (event.exception is InterruptedException) {
            Log.d(tag, "InterruptedException detected. Assuming process was manually stopped.")
            BotStatus.setOutcome(BotStatus.Outcome.STOPPED_BY_USER, "You stopped the bot")
        } else {
            Log.d(tag, "Process has finished running but an exception(s) were detected.")

            BotStatus.setOutcome(BotStatus.Outcome.CRASHED, event.exception.javaClass.simpleName)

            MessageLog.e(tag, "$appName encountered an Exception: ${event.exception.stackTraceToString()}")

            if (event.exception.stackTraceToString().length >= 2500) {
                Log.d(tag, "Splitting up Discord message to avoid being cut off due to character limit.")
                val halfLength: Int = event.exception.stackTraceToString().length / 2
                val message1: String = event.exception.stackTraceToString().substring(0, halfLength)
                val message2: String = event.exception.stackTraceToString().substring(halfLength)

                DiscordUtils.queue.add("> ${MessageLog.getSystemTimeString()} Encountered exception: \n$message1")
                DiscordUtils.queue.add("> ${MessageLog.getSystemTimeString()} $message2")
            } else {
                DiscordUtils.queue.add("> ${MessageLog.getSystemTimeString()} Encountered exception: \n${event.exception.stackTraceToString()}")
            }

            isException = true

            // Wait to make sure Discord message queue gets fully processed before terminating.
            if (DiscordUtils.enableDiscordNotifications) {
                Thread.sleep(2000)
            }

            performCleanUp()
        }
    }
}
