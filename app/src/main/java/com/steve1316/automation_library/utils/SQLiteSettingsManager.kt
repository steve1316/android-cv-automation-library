package com.steve1316.automation_library.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Manages settings persistence using SQLite database.
 * Reads settings from the same database used by the React Native frontend.
 */
class SQLiteSettingsManager(private val context: Context) {
    companion object {
        private const val TAG = "SQLiteSettingsManager"
        private const val DATABASE_NAME = "settings.db"
        private const val TABLE_SETTINGS = "settings"
        
        // Database columns.
        private const val COLUMN_ID = "id"
        private const val COLUMN_CATEGORY = "category"
        private const val COLUMN_KEY = "key"
        private const val COLUMN_VALUE = "value"
        private const val COLUMN_UPDATED_AT = "updated_at"
    }

    private var database: SQLiteDatabase? = null
    private var isInitialized = false

    /**
     * Initialize the database connection by opening the existing database file.
     * The database is created by expo-sqlite in the app's files directory.
     *
     * @return True if initialization was successful, false otherwise.
     */
    fun initialize(): Boolean {
        if (isInitialized) {
            Log.d(TAG, "Database already initialized.")
            return true
        }

        val dbFile = File(context.filesDir, "SQLite/$DATABASE_NAME")

        try {
            Log.d(TAG, "Attempting to open database at: SQLite/${DATABASE_NAME}")

            if (!dbFile.exists()) {
                Log.d(TAG, "Database file does not exist at: ${dbFile.absolutePath}")
            } else if (!dbFile.canRead()) {
                Log.d(TAG, "Database file is not readable at: ${dbFile.absolutePath}")
            } else {
                Log.d(TAG, "Found database file at: ${dbFile.absolutePath} (${dbFile.length()} bytes)")

                // Open the existing database in read-only mode first to verify it's accessible.
                database = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                Log.d(TAG, "Database opened successfully in read-only mode.")

                // Verify the database has the expected table structure.
                if (!verifyDatabaseStructure()) {
                    Log.d(TAG, "Database structure verification failed for: ${dbFile.absolutePath}")
                    database?.close()
                    database = null
                } else {
                    // Close read-only connection and open in read-write mode.
                    database?.close()
                    database = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                    Log.d(TAG, "Database opened successfully in read-write mode at: ${dbFile.absolutePath}")

                    isInitialized = true
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open database at ${dbFile.absolutePath}: ${e.message}", e)
            database?.close()
            database = null
        }

        Log.e(TAG, "Failed to find or open the SQLite database.")
        
        // If no database exists, try to create one in the default location.
        Log.d(TAG, "Now attempting to create a new SQLite database at ${dbFile.absolutePath}...")
        return createNewDatabase()
    }

    /**
     * Create a new database if none exists.
     *
     * @return True if the database was created successfully, false otherwise.
     */
    private fun createNewDatabase(): Boolean {
        try {
            val dbFile = File(context.filesDir, "SQLite/$DATABASE_NAME")
            val sqliteDir = File(context.filesDir, "SQLite")
            
            // Create the SQLite directory if it doesn't exist.
            if (!sqliteDir.exists()) {
                sqliteDir.mkdirs()
                Log.d(TAG, "Created SQLite directory: ${sqliteDir.absolutePath}")
            }
            
            // Create a new database.
            database = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
            Log.d(TAG, "Created new database at: ${dbFile.absolutePath}")
            
            // Create the settings table.
            database?.execSQL("""
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
            database?.execSQL("""
                CREATE INDEX IF NOT EXISTS idx_settings_category_key
                ON $TABLE_SETTINGS($COLUMN_CATEGORY, `$COLUMN_KEY`)
            """)
            Log.d(TAG, "Created index.")
            
            isInitialized = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create new database: ${e.message}", e)
            database?.close()
            database = null
            isInitialized = false
            return false
        }
    }

    /**
     * Verify that the database has the expected table structure.
     *
     * @return True if the database has the expected structure, false otherwise.
     */
    private fun verifyDatabaseStructure(): Boolean {
        return try {
            Log.d(TAG, "Verifying database structure - checking for table '$TABLE_SETTINGS'")
            val cursor = database?.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(TABLE_SETTINGS)
            )
            val hasTable = cursor?.moveToFirst() == true
            cursor?.close()
            
            if (hasTable) {
                Log.d(TAG, "Database structure verification successful - table '$TABLE_SETTINGS' found")
            } else {
                Log.w(TAG, "Database structure verification failed - table '$TABLE_SETTINGS' not found")
            }
            hasTable
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying database structure: ${e.message}", e)
            false
        }
    }

    /**
     * Check if the database is available and initialized.
     *
     * @return True if the database is initialized and open, false otherwise.
     */
    fun isAvailable(): Boolean {
        val available = isInitialized && database != null && database?.isOpen == true
        Log.d(TAG, "Database availability check: initialized=$isInitialized, database=${database != null}, open=${database?.isOpen}, available=$available")
        return available
    }

    /**
     * Load a specific setting from the database.
     *
     * @param category The settings category (e.g., "general", "racing", "training").
     * @param key The setting key.
     * @return The setting value or null if not found.
     */
    fun loadSetting(category: String, key: String): String? {
        if (!isAvailable()) {
            Log.e(TAG, "Database not available.")
            return null
        }

        return try {
            val cursor = database?.query(
                TABLE_SETTINGS,
                arrayOf(COLUMN_VALUE),
                "$COLUMN_CATEGORY = ? AND `$COLUMN_KEY` = ?",
                arrayOf(category, key),
                null, null, null
            )

            val value = if (cursor?.moveToFirst() == true) {
                val result = cursor.getString(0)
                result
            } else {
                Log.e(TAG, "Setting not found: $category.$key")
                null
            }

            cursor?.close()
            value
        } catch (e: Exception) {
            Log.e(TAG, "Error loading setting $category.$key: ${e.message}", e)
            null
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
        val value = loadSetting(category, key) 
            ?: throw RuntimeException("Setting not found: $category.$key")
        return try {
            value.toBoolean()
        } catch (e: Exception) {
            throw RuntimeException("Error parsing boolean value for $category.$key: $value", e)
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
        val value = loadSetting(category, key) 
            ?: throw RuntimeException("Setting not found: $category.$key")
        return try {
            value.toInt()
        } catch (e: Exception) {
            throw RuntimeException("Error parsing integer value for $category.$key: $value", e)
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
        val value = loadSetting(category, key) 
            ?: throw RuntimeException("Setting not found: $category.$key")
        return try {
            value.toDouble()
        } catch (e: Exception) {
            throw RuntimeException("Error parsing double value for $category.$key: $value", e)
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
        val value = loadSetting(category, key) 
            ?: throw RuntimeException("Setting not found: $category.$key")
        return try {
            val jsonArray = JSONObject("{\"array\": $value}").getJSONArray("array")
            val list = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getString(i))
            }
            list
        } catch (e: Exception) {
            throw RuntimeException("Error parsing string array value for $category.$key: $value", e)
        }
    }

    /**
     * Check if the database file exists and is accessible.
     *
     * @return True if any of the expected database file paths exist and are readable.
     */
    fun isDatabaseAvailable(): Boolean {
        val possiblePaths = listOf(
            File(context.filesDir, "SQLite/$DATABASE_NAME"),
            File(context.filesDir, DATABASE_NAME),
            File(context.filesDir, "databases/$DATABASE_NAME"),
            context.getDatabasePath(DATABASE_NAME)
        )
        
        return possiblePaths.any { it.exists() && it.canRead() }
    }

    /**
     * Get the database instance for direct access.
     * This should only be used by classes that need direct database access.
     *
     * @return The SQLiteDatabase instance if available, or NULL otherwise.
     */
    fun getDatabase(): SQLiteDatabase? {
        return if (isAvailable()) database else null
    }

    /**
     * Close the database connection.
     */
    fun close() {
        database?.close()
        database = null
        isInitialized = false
        Log.d(TAG, "Database connection closed.")
    }
}

