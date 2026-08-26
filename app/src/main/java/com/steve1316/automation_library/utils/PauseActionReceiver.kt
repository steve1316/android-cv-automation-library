package com.steve1316.automation_library.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Receives the notification's Pause and Resume actions. Runs in the main process because `BotHold` is in-process state.
 */
class PauseActionReceiver : BroadcastReceiver() {
    companion object {
        /** Intent action for the notification's Pause button. */
        const val ACTION_PAUSE = "com.steve1316.automation_library.action.PAUSE"

        /** Intent action for the notification's Resume button. */
        const val ACTION_RESUME = "com.steve1316.automation_library.action.RESUME"

        /** How long the notification shade takes to finish closing before the bot may resume, in milliseconds. */
        private const val SHADE_CLOSE_DELAY_MS = 600L
    }

    /**
     * Pauses or resumes the bot to match the tapped action, and closes the notification shade so the user is back on the game.
     *
     * Resume waits for the shade to finish closing. Otherwise the bot reads the shade as an unknown screen and its fallback tap can open the app.
     *
     * @param context The receiver's context.
     * @param intent The broadcast, whose action is `ACTION_PAUSE` or `ACTION_RESUME`.
     */
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_PAUSE -> {
                BotHold.requestPause()
                MyAccessibilityService.dismissNotificationShade(context)
            }
            ACTION_RESUME -> {
                MyAccessibilityService.dismissNotificationShade(context)
                Handler(Looper.getMainLooper()).postDelayed({ BotHold.resume() }, SHADE_CLOSE_DELAY_MS)
            }
        }
    }
}
