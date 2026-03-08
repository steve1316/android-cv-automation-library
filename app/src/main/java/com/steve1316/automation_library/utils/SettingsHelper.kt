package com.steve1316.automation_library.utils

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.steve1316.automation_library.data.SharedData

/**
 * Helper class to provide easy access to settings from SQLite database.
 * This class provides a centralized way to access settings throughout the app.
 */
object SettingsHelper {
    private const val TAG = "${SharedData.loggerTag}SettingsHelper"
    @SuppressLint("StaticFieldLeak")
    private var settingsManager: SQLiteSettingsManager? = null

    /**
     * Initialize the settings helper with a context.
     * This should be called once during app initialization.
     *
     * @param context The application context.
     */
    fun initialize(context: Context) {
        // If already initialized, close it first.
        if (settingsManager != null) {
            settingsManager?.close()
        }

        settingsManager = SQLiteSettingsManager(context)
        if (settingsManager?.initialize() == true) {
            Log.d(TAG, "Settings helper initialized successfully.")
        } else {
            Log.w(TAG, "Failed to initialize settings helper.")
        }
    }

    /**
     * Re-initializes the settings manager to ensure we have the freshest settings from the database.
     * This is useful when settings are updated in the frontend and need to be reflected in the backend without restarting the service.
     *
     * @param context The application context.
     */
    fun refresh(context: Context) {
        Log.d(TAG, "Refreshing settings helper...")
        initialize(context)
    }

	/**
	 * Get a boolean setting value.
	 *
	 * @param category The settings category.
	 * @param key The setting key.
	 * @param defaultValue The default value if setting doesn't exist.
	 * @return The boolean value of the setting.
	 */
	fun getBooleanSetting(category: String, key: String, defaultValue: Boolean = false): Boolean {
		return try {
			settingsManager?.getBooleanSetting(category, key) ?: defaultValue
		} catch (e: Exception) {
			Log.e(TAG, "Setting not found: $category.$key. Using default: $defaultValue")
			defaultValue
		}
	}

	/**
	 * Get an integer setting value.
	 *
	 * @param category The settings category.
	 * @param key The setting key.
	 * @param defaultValue The default value if setting doesn't exist.
	 * @return The integer value of the setting.
	 */
	fun getIntSetting(category: String, key: String, defaultValue: Int = 0): Int {
		return try {
			settingsManager?.getIntSetting(category, key) ?: defaultValue
		} catch (e: Exception) {
			Log.e(TAG, "Setting not found: $category.$key. Using default: $defaultValue")
			defaultValue
		}
	}

	/**
	 * Get a float setting value.
	 *
	 * @param category The settings category.
	 * @param key The setting key.
	 * @param defaultValue The default value if setting doesn't exist.
	 * @return The float value of the setting.
	 */
	fun getFloatSetting(category: String, key: String, defaultValue: Float = 0.0f): Float {
		return try {
			settingsManager?.getDoubleSetting(category, key)?.toFloat() ?: defaultValue
		} catch (e: Exception) {
			Log.e(TAG, "Setting not found: $category.$key. Using default: $defaultValue")
			defaultValue
		}
	}

	/**
	 * Get a double setting value.
	 *
	 * @param category The settings category.
	 * @param key The setting key.
	 * @param defaultValue The default value if setting doesn't exist.
	 * @return The double value of the setting.
	 */
	fun getDoubleSetting(category: String, key: String, defaultValue: Double = 0.0): Double {
		return try {
			settingsManager?.getDoubleSetting(category, key) ?: defaultValue
		} catch (e: Exception) {
			Log.e(TAG, "Setting not found: $category.$key. Using default: $defaultValue")
			defaultValue
		}
	}

	/**
	 * Get a string setting value.
	 *
	 * @param category The settings category.
	 * @param key The setting key.
	 * @param defaultValue The default value if setting doesn't exist.
	 * @return The string value of the setting.
	 */
	fun getStringSetting(category: String, key: String, defaultValue: String = ""): String {
		return try {
			settingsManager?.getStringSetting(category, key) ?: defaultValue
		} catch (e: Exception) {
			Log.e(TAG, "Setting not found: $category.$key. Using default: $defaultValue")
			defaultValue
		}
	}

	/**
	 * Get a string array setting value.
	 *
	 * @param category The settings category.
	 * @param key The setting key.
	 * @param defaultValue The default value if setting doesn't exist.
	 * @return The list of strings for the setting.
	 */
	fun getStringArraySetting(category: String, key: String, defaultValue: List<String> = emptyList()): List<String> {
		return try {
			settingsManager?.getStringArraySetting(category, key) ?: defaultValue
		} catch (e: Exception) {
			Log.e(TAG, "Setting not found: $category.$key. Using default: $defaultValue")
			defaultValue
		}
	}

	/**
	 * Check if the settings manager is available.
	 *
	 * @return True if the settings manager is initialized and the database is available.
	 */
	fun isAvailable(): Boolean {
		return settingsManager != null && settingsManager?.isDatabaseAvailable() == true
	}
}

