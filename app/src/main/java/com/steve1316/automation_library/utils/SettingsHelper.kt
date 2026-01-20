package com.steve1316.automation_library.utils

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log

/**
 * Helper class to provide easy access to settings from SQLite database.
 * This class provides a centralized way to access settings throughout the app.
 */
object SettingsHelper {
    private const val TAG = "SettingsHelper"
    @SuppressLint("StaticFieldLeak")
    private var settingsManager: SQLiteSettingsManager? = null

    /**
     * Initialize the settings helper with a context.
     * This should be called once during app initialization.
     *
     * @param context The application context.
     */
    fun initialize(context: Context) {
        settingsManager = SQLiteSettingsManager(context)
        if (settingsManager?.initialize() == true) {
            Log.d(TAG, "Settings helper initialized successfully.")
        } else {
            Log.w(TAG, "Failed to initialize settings helper.")
        }
    }

    /**
     * Get a boolean setting value.
     *
     * @param category The settings category.
     * @param key The setting key.
     * @return The boolean value of the setting.
     * @throws RuntimeException if setting doesn't exist.
     */
    fun getBooleanSetting(category: String, key: String): Boolean {
        return settingsManager?.getBooleanSetting(category, key) 
            ?: throw RuntimeException("Setting not found: $category.$key")
    }

    /**
     * Get an integer setting value.
     *
     * @param category The settings category.
     * @param key The setting key.
     * @return The integer value of the setting.
     * @throws RuntimeException if setting doesn't exist.
     */
    fun getIntSetting(category: String, key: String): Int {
        return settingsManager?.getIntSetting(category, key)
            ?: throw RuntimeException("Setting not found: $category.$key")
    }

    /**
     * Get a double setting value.
     *
     * @param category The settings category.
     * @param key The setting key.
     * @return The double value of the setting.
     * @throws RuntimeException if setting doesn't exist.
     */
    fun getDoubleSetting(category: String, key: String): Double {
        return settingsManager?.getDoubleSetting(category, key)
            ?: throw RuntimeException("Setting not found: $category.$key")
    }

    /**
     * Get a string setting value.
     *
     * @param category The settings category.
     * @param key The setting key.
     * @return The string value of the setting.
     * @throws RuntimeException if setting doesn't exist.
     */
    fun getStringSetting(category: String, key: String): String {
        return settingsManager?.getStringSetting(category, key)
            ?: throw RuntimeException("Setting not found: $category.$key")
    }

    /**
     * Get a string array setting value.
     *
     * @param category The settings category.
     * @param key The setting key.
     * @return The list of strings for the setting.
     * @throws RuntimeException if setting doesn't exist.
     */
    fun getStringArraySetting(category: String, key: String): List<String> {
        return settingsManager?.getStringArraySetting(category, key)
            ?: throw RuntimeException("Setting not found: $category.$key")
    }

    /**
     * Check if the settings manager is available.
     *
     * @return True if the settings manager is initialized and the database is available.
     */
    fun isAvailable(): Boolean {
        return settingsManager != null && settingsManager?.isDatabaseAvailable() == true
    }

    /**
     * Get a boolean setting value with a default fallback.
     *
     * @param category The settings category.
     * @param key The setting key.
     * @param defaultValue The default value if setting doesn't exist.
     * @return The boolean value of the setting, or the default value.
     */
    fun getBooleanSettingSafe(category: String, key: String, defaultValue: Boolean): Boolean {
        return try {
            settingsManager?.getBooleanSetting(category, key) ?: defaultValue
        } catch (e: Exception) {
            defaultValue
        }
    }

    /**
     * Get an integer setting value with a default fallback.
     *
     * @param category The settings category.
     * @param key The setting key.
     * @param defaultValue The default value if setting doesn't exist.
     * @return The integer value of the setting, or the default value.
     */
    fun getIntSettingSafe(category: String, key: String, defaultValue: Int): Int {
        return try {
            settingsManager?.getIntSetting(category, key) ?: defaultValue
        } catch (e: Exception) {
            defaultValue
        }
    }

    /**
     * Get a float setting value with a default fallback.
     *
     * @param category The settings category.
     * @param key The setting key.
     * @param defaultValue The default value if setting doesn't exist.
     * @return The float value of the setting, or the default value.
     */
    fun getFloatSettingSafe(category: String, key: String, defaultValue: Float): Float {
        return try {
            settingsManager?.getDoubleSetting(category, key)?.toFloat() ?: defaultValue
        } catch (e: Exception) {
            defaultValue
        }
    }
}

