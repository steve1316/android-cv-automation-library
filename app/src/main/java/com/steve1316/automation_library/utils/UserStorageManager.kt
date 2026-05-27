package com.steve1316.automation_library.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.steve1316.automation_library.data.SharedData
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Manages user-visible storage for an automation app via the Storage Access Framework (SAF).
 * When the user picks a folder via `ACTION_OPEN_DOCUMENT_TREE`, this class persists the tree
 * `Uri` and routes file operations through SAF. When no `Uri` is configured, it falls back to
 * the legacy `getExternalFilesDir()` paths so existing apps that haven't migrated keep working.
 *
 * Subdirectories like `logs/`, `recordings/`, and `backups/` are created lazily on first use.
 *
 * @param context Any context. The application context is captured internally to avoid leaks.
 */
class UserStorageManager private constructor(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "${SharedData.loggerTag}UserStorageManager"
        private const val PREFS_NAME = "UserStorage"
        private const val PREF_TREE_URI = "treeUri"

        @Volatile
        private var instance: UserStorageManager? = null

        /** Get the singleton instance, built off the application context.
         *
         * @param context Any context. The application context is used internally.
         *
         * @return The shared `UserStorageManager` instance.
         */
        fun getInstance(context: Context): UserStorageManager = instance ?: synchronized(this) {
            instance ?: UserStorageManager(context.applicationContext).also { instance = it }
        }
    }

    /** Persist the tree `Uri` chosen by the user from `ACTION_OPEN_DOCUMENT_TREE`.
     * Takes a persistable read/write permission grant on the `Uri` so it survives device reboots.
     * Pass `null` to clear the configured folder and fall back to legacy storage paths.
     *
     * @param uri The tree `Uri` returned from the SAF picker, or `null` to clear.
     */
    fun setTreeUri(uri: Uri?) {
        if (uri == null) {
            prefs.edit().remove(PREF_TREE_URI).apply()
            Log.d(TAG, "Cleared tree Uri.")
            return
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            Log.e(TAG, "Could not take persistable Uri permission for $uri", e)
            return
        }
        prefs.edit().putString(PREF_TREE_URI, uri.toString()).apply()
        Log.d(TAG, "Stored tree Uri: $uri")
    }

    /** Read the currently configured tree `Uri`, or `null` if the user hasn't picked a folder yet.
     *
     * @return The configured tree `Uri`, or `null` when none is stored.
     */
    fun getTreeUri(): Uri? {
        val raw = prefs.getString(PREF_TREE_URI, null) ?: return null
        return Uri.parse(raw)
    }

    /** Whether a user-picked folder is currently configured.
     *
     * @return `true` if a tree `Uri` is stored, `false` otherwise.
     */
    fun isConfigured(): Boolean = getTreeUri() != null

    /** Verify that the currently configured tree `Uri` is still writable by creating and deleting
     * a probe file. Call this on app start or after restoration to catch revoked grants or
     * deleted folders before the bot tries to write logs.
     *
     * @return `true` if a probe file could be created and deleted under the tree.
     */
    fun validateAccess(): Boolean {
        val tree = treeDocument() ?: return false
        return try {
            val probe = tree.createFile("application/octet-stream", ".probe-${System.currentTimeMillis()}")
            val ok = probe != null && probe.exists()
            probe?.delete()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "validateAccess failed", e)
            false
        }
    }

    /** Open an `OutputStream` for writing a file inside the named subdirectory. The subdirectory
     * is created if it doesn't exist. When no tree `Uri` is configured, falls back to writing
     * under `getExternalFilesDir()`. Existing files with the same name are deleted first.
     *
     * Callers are responsible for closing the returned stream.
     *
     * @param subdir The subdirectory name under the tree root (e.g. `"logs"`).
     * @param filename The file to open or create.
     * @param mimeType The MIME type used when creating the file via SAF. Defaults to `application/octet-stream`.
     *
     * @return An `OutputStream` ready for writing, or `null` on failure.
     */
    fun openOutputStream(subdir: String, filename: String, mimeType: String = "application/octet-stream"): OutputStream? {
        val tree = treeDocument() ?: return legacyOpenOutput(subdir, filename)
        return try {
            val dir = subdirectory(tree, subdir) ?: return null
            dir.findFile(filename)?.delete()
            val file = dir.createFile(mimeType, filename) ?: return null
            context.contentResolver.openOutputStream(file.uri)
        } catch (e: Exception) {
            Log.e(TAG, "openOutputStream($subdir/$filename) failed", e)
            null
        }
    }

    /** Open an `InputStream` for reading a file from the named subdirectory. When no tree `Uri`
     * is configured, falls back to reading from `getExternalFilesDir()`. Callers are responsible
     * for closing the returned stream.
     *
     * @param subdir The subdirectory name under the tree root.
     * @param filename The file to open.
     *
     * @return An `InputStream`, or `null` if the file does not exist or could not be opened.
     */
    fun openInputStream(subdir: String, filename: String): InputStream? {
        val tree = treeDocument() ?: return legacyOpenInput(subdir, filename)
        return try {
            val dir = tree.findFile(subdir) ?: return null
            val file = dir.findFile(filename) ?: return null
            context.contentResolver.openInputStream(file.uri)
        } catch (e: Exception) {
            Log.e(TAG, "openInputStream($subdir/$filename) failed", e)
            null
        }
    }

    /** List the `DocumentFile` entries inside a subdirectory under the configured tree. Returns
     * an empty list when the subdirectory does not exist or no tree `Uri` is configured. Legacy
     * paths are not surfaced here since they use the `File` API. Callers that need to enumerate
     * legacy files should check `isConfigured` first and read `getExternalFilesDir()` directly.
     *
     * @param subdir The subdirectory name under the tree root.
     *
     * @return The entries inside the subdirectory, or an empty list.
     */
    fun listFiles(subdir: String): List<DocumentFile> {
        val tree = treeDocument() ?: return emptyList()
        val dir = tree.findFile(subdir) ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().toList()
    }

    /** Delete a file from a subdirectory. When no tree `Uri` is configured, falls back to
     * deleting from `getExternalFilesDir()`.
     *
     * @param subdir The subdirectory name under the tree root.
     * @param filename The file to delete.
     *
     * @return `true` if the file existed and was deleted, `false` otherwise.
     */
    fun deleteFile(subdir: String, filename: String): Boolean {
        val tree = treeDocument()
        if (tree == null) {
            val legacy = legacyFile(subdir, filename)
            return legacy.exists() && legacy.delete()
        }
        val dir = tree.findFile(subdir) ?: return false
        val file = dir.findFile(filename) ?: return false
        return file.delete()
    }

    /** A short human-readable label for the active storage root, for use in log lines and UI.
     * Returns the tree document name when SAF is configured, otherwise the legacy external-files
     * absolute path.
     *
     * @return A label like `"UmaAutomation"` or `"/storage/emulated/0/Android/data/.../files"`.
     */
    fun pathLabel(): String {
        val tree = treeDocument()
        if (tree != null) return tree.name ?: "user-folder"
        return context.getExternalFilesDir(null)?.absolutePath ?: "filesDir"
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Helpers

    /** Resolve the persisted tree `Uri` to a writable `DocumentFile`. Returns `null` when no `Uri`
     * is configured or the grant has been revoked.
     */
    private fun treeDocument(): DocumentFile? {
        val uri = getTreeUri() ?: return null
        val doc = DocumentFile.fromTreeUri(context, uri)
        if (doc == null || !doc.canWrite()) {
            Log.w(TAG, "Tree Uri $uri is no longer writable.")
            return null
        }
        return doc
    }

    /** Find an existing subdirectory under the tree by name, creating it if it doesn't exist.
     */
    private fun subdirectory(tree: DocumentFile, name: String): DocumentFile? {
        val existing = tree.findFile(name)
        if (existing != null && existing.isDirectory) return existing
        return tree.createDirectory(name)
    }

    /** Resolve a legacy `File` under `getExternalFilesDir()/<subdir>/<filename>`, creating the
     * subdirectory if necessary. Falls back to `filesDir` when external storage is unavailable.
     */
    private fun legacyFile(subdir: String, filename: String): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, subdir)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, filename)
    }

    /** Open a `FileOutputStream` against the legacy `getExternalFilesDir()/<subdir>/<filename>`
     * location. Used as the fallback when no SAF tree `Uri` is configured.
     *
     * @param subdir The subdirectory name under the legacy root.
     * @param filename The file to open or create.
     *
     * @return A `FileOutputStream` ready for writing, or `null` if the file could not be opened.
     */
    private fun legacyOpenOutput(subdir: String, filename: String): OutputStream? {
        return try {
            FileOutputStream(legacyFile(subdir, filename))
        } catch (e: Exception) {
            Log.e(TAG, "legacyOpenOutput($subdir/$filename) failed", e)
            null
        }
    }

    /** Open a `FileInputStream` against the legacy `getExternalFilesDir()/<subdir>/<filename>`
     * location. Used as the fallback when no SAF tree `Uri` is configured.
     *
     * @param subdir The subdirectory name under the legacy root.
     * @param filename The file to open.
     *
     * @return A `FileInputStream`, or `null` if the file does not exist or could not be opened.
     */
    private fun legacyOpenInput(subdir: String, filename: String): InputStream? {
        return try {
            val file = legacyFile(subdir, filename)
            if (!file.exists()) null else FileInputStream(file)
        } catch (e: Exception) {
            Log.e(TAG, "legacyOpenInput($subdir/$filename) failed", e)
            null
        }
    }
}
