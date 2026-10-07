package com.steve1316.automation_library.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Unit tests for migrating legacy files when no user folder is picked. The SAF paths need a device. */
class UserStorageManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var storage: UserStorageManager

    /** A `SharedPreferences` backed by a map, so the manager can read and write its tree Uri on the JVM. */
    private class FakePrefs : SharedPreferences {
        /** The stored values by key. */
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values

        override fun getString(key: String?, defValue: String?): String? = values[key] as String? ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues

        override fun getInt(key: String?, defValue: Int): Int = defValue

        override fun getLong(key: String?, defValue: Long): Long = defValue

        override fun getFloat(key: String?, defValue: Float): Float = defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(values)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    /** An editor that writes straight into the `FakePrefs` map. */
    private class FakeEditor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { values[key!!] = value }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this

        override fun remove(key: String?): SharedPreferences.Editor = apply { values.remove(key) }

        override fun clear(): SharedPreferences.Editor = apply { values.clear() }

        override fun commit(): Boolean = true

        override fun apply() {}
    }

    /** A `Context` whose external files directory is a temporary folder. */
    private class FakeContext(private val externalFilesDir: File) : ContextWrapper(null) {
        private val prefs = FakePrefs()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs

        override fun getExternalFilesDir(type: String?): File = externalFilesDir

        override fun getFilesDir(): File = externalFilesDir
    }

    @Before
    fun setUp() {
        // The manager is a process-wide singleton, so drop the previous test's instance.
        UserStorageManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        root = tempFolder.newFolder("external")
        storage = UserStorageManager.getInstance(FakeContext(root))
    }

    /**
     * Writes a file under the legacy root.
     *
     * @param subdir The subdirectory, e.g. "logs".
     * @param name The file name.
     * @param content The file content.
     * @return The written file.
     */
    private fun legacyFile(subdir: String, name: String, content: String): File = File(root, subdir).apply { mkdirs() }.let { File(it, name).apply { writeText(content) } }

    @Test
    fun moveWithoutPickedFolderKeepsLegacyFiles() {
        val log = legacyFile("logs", "log @ 2026-10-01 10_00_00.txt", "seeded log")
        val recording = legacyFile("recordings", "recording_20261001_100000.mp4", "seeded video")
        assertFalse(storage.isConfigured())

        val result = storage.migrateLegacyFiles("move")

        assertEquals("NO_DESTINATION", result.error)
        assertEquals(0, result.movedLogs)
        assertEquals(0, result.movedRecordings)
        assertEquals(2, result.remaining)
        assertEquals("seeded log", log.readText())
        assertEquals("seeded video", recording.readText())
    }

    @Test
    fun deleteWithoutPickedFolderStillDeletesLegacyFiles() {
        val log = legacyFile("logs", "log @ 2026-10-01 10_00_00.txt", "seeded log")

        val result = storage.migrateLegacyFiles("delete")

        assertNull(result.error)
        assertEquals(1, result.movedLogs)
        assertFalse(log.exists())
    }
}
