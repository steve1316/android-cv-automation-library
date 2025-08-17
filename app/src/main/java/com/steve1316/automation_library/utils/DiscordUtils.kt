package com.steve1316.automation_library.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.steve1316.automation_library.R
import com.steve1316.automation_library.data.SharedData
import org.javacord.api.DiscordApi
import org.javacord.api.DiscordApiBuilder
import org.javacord.api.entity.channel.PrivateChannel
import org.javacord.api.entity.user.User
import java.util.*


/**
 * This class takes care of notifying users of status updates via Discord private DMs.
 *
 * @param myContext The application's context.
 */
class DiscordUtils(myContext: Context) {
	private val tag: String = "${SharedData.loggerTag}DiscordUtils"
	private var appName: String = myContext.getString(R.string.app_name)

	private val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(myContext)
	private val discordToken: String = sharedPreferences.getString("discordToken", "")!!
	private val discordUserID: String = sharedPreferences.getString("discordUserID", "")!!

	companion object {
		val queue: Queue<String> = LinkedList()
		lateinit var client: DiscordApi
		var privateChannel: PrivateChannel? = null
	}

	private fun sendMessage(message: String) {
		privateChannel?.sendMessage(message)?.join()
	}

	fun main() {
		Log.d(tag, "Starting Discord process now...")

		try {
			client = DiscordApiBuilder().setToken(discordToken).login().join()
		} catch (_: Exception) {
			Log.d(tag, "[DISCORD] Failed to connect to Discord API using provided token.")
			return
		}

		val user: User
		try {
			user = client.getUserById(discordUserID).join()
		} catch (_: Exception) {
			Log.d(tag, "[DISCORD] Failed to find user using provided user ID.")
			return
		}

		try {
			privateChannel = user.openPrivateChannel().join()
		} catch (_: Exception) {
			Log.d(tag, "[DISCORD] Failed to open private channel with user.")
			return
		}

		Log.d(tag, "Successfully fetched reference to user and their private channel.")

		queue.add("```diff\n+ Successful mobile connection to Discord API for $appName\n```")

		try {
			// Loop and send any messages inside the Queue.
			while (true) {
				if (queue.isNotEmpty()) {
					val message = queue.remove()
					sendMessage(message)

					if (message.contains("Terminated connection to Discord API")) {
						break
					}
				}
			}

			Log.d(tag, "Terminated connection to Discord API.")
		} catch (e: Exception) {
			Log.e(tag, e.stackTraceToString())
		}
	}
}