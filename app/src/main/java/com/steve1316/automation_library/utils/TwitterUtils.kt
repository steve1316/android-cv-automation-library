package com.steve1316.automation_library.utils

import android.os.StrictMode
import com.steve1316.automation_library.data.SharedData
import twitter4j.Twitter
import twitter4j.v1.Query
import twitter4j.v1.TwitterV1

/**
 * Provides the functions needed to perform Twitter API-related tasks such as searching for tweets.
 *
 * Available helper methods are connect() and testConnection().
 *
 * @property test If enabled, initialization will skip first connection in favour of manually calling testConnection() later.
 */
open class TwitterUtils(private val test: Boolean = false) {
	private val tag: String = "${SharedData.loggerTag}TwitterRoomFinder"

	open var twitterAPIKey: String = ""
	open var twitterAPIKeySecret: String = ""
	open var twitterAccessToken: String = ""
	open var twitterAccessTokenSecret: String = ""

	// For Twitter API v1.1
	protected lateinit var oldTwitterClient: TwitterV1

	/**
	 * Connect to Twitter API V1.1
	 *
	 */
	fun connect() {
		if (!test) {
			MessageLog.d(tag, "\n[TWITTER] Authenticating provided consumer keys and access tokens with the Twitter API V1.1...")
			val result = testConnection()
			if (result == "Test successfully completed.") {
				MessageLog.d(tag, "[TWITTER] Successfully connected to the Twitter API V1.1.")
			} else {
				throw Exception(result)
			}
		}
	}

	/**
	 * Test connection to the API using the consumer keys/tokens for V1.1.
	 *
	 * @return Either a success message or an error message depending on the connection to the API.
	 */
	fun testConnection(): String {
		// Allow Network IO to be run on the main thread without throwing the NetworkOnMainThreadException.
		val policy: StrictMode.ThreadPolicy = StrictMode.ThreadPolicy.Builder().permitAll().build()
		StrictMode.setThreadPolicy(policy)

		val size = try {
			// Create the Twitter client object to use the Twitter API V1.1.
			oldTwitterClient = Twitter.newBuilder().apply {
				oAuthConsumer(twitterAPIKey, twitterAPIKeySecret)
				oAuthAccessToken(twitterAccessToken, twitterAccessTokenSecret)
			}.build().v1()

			val queryResult = oldTwitterClient.search().search(Query.of("Hello World"))
			queryResult.count
		} catch (_: Exception) {
			MessageLog.e(tag, "Cannot connect to Twitter API v1.1 due to keys and access tokens being incorrect.")
			return "Cannot connect to Twitter API v1.1 due to keys and access tokens being incorrect."
		}

		return if (size > 0) {
			"Test successfully completed."
		} else {
			"Connection was successful but test search came up empty."
		}
	}
}
