package com.steve1316.automation_library.utils

import android.content.Context
import android.util.Log
import com.steve1316.automation_library.R
import com.steve1316.automation_library.data.SharedData
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.channel.DmChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import java.util.*


/**
 * This class takes care of notifying users of status updates via Discord private DMs.
 *
 * @param myContext The application's context.
 */
open class DiscordUtils(myContext: Context) {
	private val tag: String = "${SharedData.loggerTag}DiscordUtils"
	private var appName: String = myContext.getString(R.string.app_name)


	companion object {
		val queue: Queue<String> = LinkedList()
		lateinit var client: Kord
		var dmChannel: DmChannel? = null
		var enableDiscordNotifications: Boolean = false

		var discordToken: String = ""
		var discordUserID: String = ""
		var isRunning: Boolean = false
	}

	/**
	 * Sends a message to the user's DM channel.
	 *
	 * @param message The message to send.
	 */
	open suspend fun sendMessage(message: String) {
		dmChannel?.createMessage(message)
	}

	/**
	 * Main entry point for the Discord integration. Connects to the Discord API,
	 * opens a DM channel with the user, and processes the message queue.
	 */
	open suspend fun main() {
		Log.d(tag, "Starting Discord process now...")

		// Load credentials from settings if not already set.
		if (discordToken == "") {
			discordToken = SettingsHelper.getStringSetting("discord", "discordToken", "")
		}
		if (discordUserID == "") {
			discordUserID = SettingsHelper.getStringSetting("discord", "discordUserID", "")
		}

		try {
			if (discordToken == "") {
				Log.d(tag, "[DISCORD] Discord token is empty. Cannot connect.")
				return
			}
			client = Kord(discordToken)
		} catch (_: Exception) {
			Log.d(tag, "[DISCORD] Failed to connect to Discord API using provided token.")
			return
		}

		val user = try {
			if (discordUserID == "") {
				Log.d(tag, "[DISCORD] Discord user ID is empty. Cannot find user.")
				return
			}
			client.getUser(Snowflake(discordUserID.toLong()))
		} catch (_: Exception) {
			Log.d(tag, "[DISCORD] Failed to find user using provided user ID.")
			return
		}

		if (user == null) {
			Log.d(tag, "[DISCORD] Failed to find user using provided user ID.")
			return
		}

		try {
			dmChannel = user.getDmChannel()
		} catch (_: Exception) {
			Log.d(tag, "[DISCORD] Failed to open DM channel with user.")
			return
		}

		Log.d(tag, "Successfully fetched reference to user and their DM channel.")

		try {
			// Loop and send any messages inside the Queue.
			while (isRunning) {
				if (queue.isNotEmpty()) {
					val message = queue.remove()
					sendMessage(message)
				}

				// Yield to other coroutines and add a small delay to avoid busy-waiting.
				yield()
				delay(100)
			}

			Log.d(tag, "Terminated connection to Discord API.")
		} catch (e: Exception) {
			Log.e(tag, e.stackTraceToString())
		} finally {
			// Cleanly shut down the Kord client.
			client.shutdown()
		}
	}
}