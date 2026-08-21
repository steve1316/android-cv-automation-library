package com.steve1316.automation_library.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the notification's Pause and Resume actions. Runs in the main process because `BotHold` is in-process state.
 */
class PauseActionReceiver : BroadcastReceiver() {
    companion object {
        /** Intent action for the notification's Pause button. */
        const val ACTION_PAUSE = "com.steve1316.automation_library.action.PAUSE"

        /** Intent action for the notification's Resume button. */
        const val ACTION_RESUME = "com.steve1316.automation_library.action.RESUME"
    }

    /**
     * Pauses or resumes the bot to match the tapped action.
     *
     * @param context The receiver's context.
     * @param intent The broadcast, whose action is `ACTION_PAUSE` or `ACTION_RESUME`.
     */
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_PAUSE -> BotHold.requestPause()
            ACTION_RESUME -> BotHold.resume()
        }
    }
}
