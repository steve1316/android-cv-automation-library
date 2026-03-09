package com.steve1316.automation_library.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteException
import android.util.Log
import org.json.JSONObject
import java.io.File
import com.steve1316.automation_library.data.SharedData

/**
 * Manages settings persistence using SQLite database.
 * Reads settings from the same database used by the React Native frontend.
 *
 * Database can be accessed directly for direct queries using the
 * readableDatabase and writableDatabase properties.
 *
 * @param context The application context.
 */
class SQLiteSettingsManager(private val context: Context) :
    SQLiteOpenHelper(context, getDatabasePath(context), null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "${SharedData.loggerTag}SQLiteSettingsManager"
        private const val DATABASE_NAME = "settings.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_SETTINGS = "settings"

        // Database columns.
        private const val COLUMN_ID = "id"
        private const val COLUMN_CATEGORY = "category"
        private const val COLUMN_KEY = "key"
        private const val COLUMN_VALUE = "value"
        private const val COLUMN_UPDATED_AT = "updated_at"

        /** Gets the absolute filepath of the database file.
         *
         * @param context The application context.
         *
         * @return The absolute filepath of the database file.
         */
        private fun getDatabasePath(context: Context): String {
            val dbFile = File(context.filesDir, "SQLite/$DATABASE_NAME")
            val sqliteDir = File(context.filesDir, "SQLite")
            // Create the SQLite directory if it doesn't exist.
            if (!sqliteDir.exists()) {
                sqliteDir.mkdirs()
                Log.d(TAG, "Created SQLite directory: ${sqliteDir.absolutePath}")
            }

            Log.d(TAG, "Using SQLite database file: ${sqliteDir.absolutePath}")
            return dbFile.absolutePath
        }

        @Volatile
        private var instance: SQLiteSettingsManager? = null
        fun getInstance(context: Context): SQLiteSettingsManager = instance ?: synchronized(this) {
            instance ?: SQLiteSettingsManager(context.applicationContext).also { instance = it }
        }
    }

    /** Called when the database is created for the first time.
     *
     * @param db The database where the tables are created.
     */
    override fun onCreate(db: SQLiteDatabase) {
        // Create the settings table.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SETTINGS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_CATEGORY TEXT NOT NULL,
                `$COLUMN_KEY` TEXT NOT NULL,
                $COLUMN_VALUE TEXT NOT NULL,
                $COLUMN_UPDATED_AT DATETIME DEFAULT CURRENT_TIMESTAMP,
                UNIQUE($COLUMN_CATEGORY, `$COLUMN_KEY`)
            )
        """)
        Log.d(TAG, "Created settings table.")
        
        // Create index for faster queries.
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_settings_category_key
            ON $TABLE_SETTINGS($COLUMN_CATEGORY, `$COLUMN_KEY`)
        """)
        Log.d(TAG, "Created index.")
    }

    /** Called when [DATABASE_VERSION] is incremented.
     *
     * Currently implements a destructive migration strategy by dropping
     * existing tables and recreating them.
     *
     * @param db The database instance.
     * @param oldVersion The current database version on the device.
     * @param newVersion The target database version defined in code.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SETTINGS")
        onCreate(db)
    }

    /** Checks if the database is available and readable.
     *
     * @return Whether the database is available and readable.
     */
    fun isAvailable(): Boolean {
        var db: SQLiteDatabase? = null
        try {
            db = this.readableDatabase
            val result: Boolean = db?.isOpen == true && db?.isReadOnly == false
            Log.d(TAG, "Database availability check: open=${db?.isOpen}, available=${result}")
            return result
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error checking if database is available:", e)
            return false
        } finally {
            db?.close()
        }
    }

    /** Gets a readable reference to the database.
     *
     * @return The readable database reference if it is available, otherwise NULL.
     */
    @Deprecated("This is a legacy function. Use SQLiteSettingsManager.readableDatabase or SQLiteSettingsManager.writableDatabase instead.")
    fun getDatabase(): SQLiteDatabase? {
        return if (isAvailable()) this.readableDatabase else null
    }

    /** Load a setting from the database using a category and key.
     *
     * @param category The category of the setting (e.g. "general", "characters", etc.)
     * @param key The setting key.
     *
     * @return The setting value or NULL if not found.
     */
    fun loadSetting(category: String, key: String): String? {
        try {
            val db = this.readableDatabase
            val cursor = db.query(
                TABLE_SETTINGS,
                arrayOf(COLUMN_VALUE),
                "$COLUMN_CATEGORY = ? AND `$COLUMN_KEY` = ?",
                arrayOf(category, key),
                null,
                null,
                null,
            )

            val result: String? = if (cursor?.moveToFirst() == true) {
                cursor.getString(0)
            } else {
                Log.e(TAG, "Setting not found: $category.$key")
                null
            }

            cursor?.close()
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error loading setting $category.$key: ${e.message}", e)
            return null
        }
    }

    /**
     * Get a boolean setting value.
     *
     * @param category The settings category.
     * @param key The setting key.
     * @return The boolean value of the setting.
     * @throws RuntimeException if setting doesn't exist or cannot be parsed.
     */
    fun getBooleanSetting(category: String, key: String): Boolean {
        val result = loadSetting(category, key)
            ?: throw RuntimeException("Setting not found: $category.$key")
        return try {
            result.toBoolean()
        } catch (e: Exception) {
            throw RuntimeException("Error parsing boolean value for $category.$key: $result", e)
        }
    }

    /**
     * Get an integer setting value.
     *
     * @param category The settings category.
     * @param key The setting key.
     * @return The integer value of the setting.
     * @throws RuntimeException if setting doesn't exist or cannot be parsed.
     */
    fun getIntSetting(category: String, key: String): Int {
        val result = loadSetting(category, key)
            ?: throw RuntimeException("Setting not found: $category.$key")
        return try {
            result.toInt()
        } catch (e: Exception) {
            throw RuntimeException("Error parsing integer value for $category.$key: $result", e)
        }
    }

    /**
     * Get a double setting value.
     *
     * @param category The settings category.
     * @param key The setting key.
     * @return The double value of the setting.
     * @throws RuntimeException if setting doesn't exist or cannot be parsed.
     */
    fun getDoubleSetting(category: String, key: String): Double {
        val result = loadSetting(category, key)
            ?: throw RuntimeException("Setting not found: $category.$key")
        return try {
            result.toDouble()
        } catch (e: Exception) {
            throw RuntimeException("Error parsing double value for $category.$key: $result", e)
        }
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
        return loadSetting(category, key)
            ?: throw RuntimeException("Setting not found: $category.$key")
    }

    /**
     * Get a JSON array setting value.
     *
     * @param category The settings category.
     * @param key The setting key.
     * @return The list of strings parsed from the JSON array.
     * @throws RuntimeException if setting doesn't exist or cannot be parsed.
     */
    fun getStringArraySetting(category: String, key: String): List<String> {
        val result = loadSetting(category, key)
            ?: throw RuntimeException("Setting not found: $category.$key")
        return try {
            val jsonArray = JSONObject("{\"array\": $result}").getJSONArray("array")
            val list = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getString(i))
            }
            list
        } catch (e: Exception) {
            throw RuntimeException("Error parsing string array value for $category.$key: $result", e)
        }
    }
}