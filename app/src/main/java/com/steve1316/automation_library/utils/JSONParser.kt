package com.steve1316.automation_library.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.steve1316.automation_library.data.SharedData
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Contains a function to read in values from the settings.json file that needs to be implemented on the developer's side over on their module and helper functions to help parse JSONArray objects.
 *
 */
open class JSONParser {
	private val tag = "${SharedData.loggerTag}JSONParser"

	/**
	 * Initialize settings from the JSON file.
	 *
	 * @param myContext The application context.
	 */
	open fun initializeSettings(myContext: Context) {
		Log.d(tag, "Loading settings from JSON file to SharedPreferences...")

		// Grab the JSON object from the file.
		val jString = File(myContext.getExternalFilesDir(null), "settings.json").bufferedReader().use { it.readText() }
		val jObj = JSONObject(jString)

		//////////////////////////////////////////////////////////////////////////
		//////////////////////////////////////////////////////////////////////////

		// Here you can parse out each property from the JSONObject via key iteration. You can create a static class
		// elsewhere to hold the JSON data. Or you can save them all into SharedPreferences.

		val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(myContext)

		//////////////////////////////////////////////////////////////////////////
		//////////////////////////////////////////////////////////////////////////

		Log.d(
			tag, "The initializeSettings method has been called from the super. Implementation needs to be done by the developers in order to load their individual settings from the " +
					"settings.json file into the SharedPreferences."
		)
	}

	/**
	 * Convert JSONArray to String ArrayList object.
	 *
	 * @param jsonArray The JSONArray object to be converted.
	 * @return The converted ArrayList object of strings.
	 */
	fun toStringArrayList(jsonArray: JSONArray): ArrayList<String> {
		val newArrayList: ArrayList<String> = arrayListOf()

		var i = 0
		while (i < jsonArray.length()) {
			newArrayList.add(jsonArray.get(i) as String)
			i++
		}

		return newArrayList
	}

	/**
	 * Convert JSONArray to Int ArrayList object.
	 *
	 * @param jsonArray The JSONArray object to be converted.
	 * @return The converted ArrayList object of integers.
	 */
	fun toIntArrayList(jsonArray: JSONArray): ArrayList<Int> {
		val newArrayList: ArrayList<Int> = arrayListOf()

		var i = 0
		while (i < jsonArray.length()) {
			newArrayList.add(jsonArray.get(i) as Int)
			i++
		}

		return newArrayList
	}
}