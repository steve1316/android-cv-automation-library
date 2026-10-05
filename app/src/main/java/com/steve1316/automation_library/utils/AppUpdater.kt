package com.steve1316.automation_library.utils

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks a GitHub repository's latest release for an app update. A newer release opens [AppUpdateDialog], which downloads the APK and hands it to
 * Android's installer without leaving the app.
 *
 * The consuming app must declare `android.permission.REQUEST_INSTALL_PACKAGES` in its manifest for the in-app install. Without it, the dialog links
 * to the release page instead.
 *
 * @property activity The [Activity] the update dialog is shown on.
 * @property config Which repository to check and how its releases are laid out.
 */
class AppUpdater(
    private val activity: Activity,
    private val config: Config,
) {
    companion object {
        private const val TIMEOUT_MS = 10_000

        /**
         * Reads the fields the dialog and installer need from a GitHub release.
         *
         * @param json The release JSON from the GitHub API.
         * @param notesEndMarkers Line prefixes where the author-written notes end, such as GitHub's generated pull request list.
         * @return The parsed [UpdateInfo], or null if the tag or page link is missing.
         */
        fun parseRelease(json: String, notesEndMarkers: List<String>): UpdateInfo? {
            val release = JSONObject(json)
            val version = release.optString("tag_name").removePrefix("v")
            val url = release.optString("html_url")
            if (version.isBlank() || url.isBlank()) return null
            val assetsJson = release.optJSONArray("assets") ?: JSONArray()
            val assets =
                (0 until assetsJson.length()).map {
                    val asset = assetsJson.getJSONObject(it)
                    ReleaseAsset(asset.optString("name"), asset.optLong("size"), asset.optString("browser_download_url"))
                }
            val notes =
                release
                    .optString("body")
                    .replace("\r\n", "\n")
                    .lineSequence()
                    .takeWhile { line -> notesEndMarkers.none { line.startsWith(it) } }
                    .joinToString("\n")
                    .trim()
            return UpdateInfo(version, url, notes, assets)
        }

        /**
         * Compares two semver-style version strings segment by segment (e.g. "5.4.8" > "5.4.7").
         *
         * @param latest The version of the latest release.
         * @param current The installed app version.
         * @return True if [latest] is strictly newer than [current].
         */
        fun isNewerVersion(latest: String, current: String): Boolean {
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        }

        /**
         * Decides what to do with the latest release. Installing ends the app, so a running bot can hold the offer back.
         *
         * @param latest The version of the latest release.
         * @param current The installed app version.
         * @param holdBack Whether the update should wait, e.g. because the bot is running.
         * @return Whether to show the update, hold it, or report the app as current.
         */
        fun decide(latest: String, current: String, holdBack: Boolean): Decision =
            when {
                !isNewerVersion(latest, current) -> Decision.UP_TO_DATE
                holdBack -> Decision.HOLD
                else -> Decision.SHOW
            }
    }

    /** Which repository to check for releases and how its release files are named. */
    data class Config(
        /** GitHub owner of the repository that publishes the releases, e.g. "steve1316". */
        val owner: String,
        /** GitHub repository name, e.g. "uma-android-automation". */
        val repo: String,
        /** The installed app version, usually the app's `BuildConfig.VERSION_NAME`. */
        val currentVersion: String,
        /** Whether a release file is the APK built for the given ABI. Defaults to names ending in "-<abi>-release.apk". */
        val isApkFor: (assetName: String, abi: String) -> Boolean = { name, abi -> name.endsWith("-$abi-release.apk") },
        /** Line prefixes in the release body where the author-written notes end. Defaults to GitHub's generated sections. */
        val notesEndMarkers: List<String> = listOf("## ", "**Full Changelog**"),
        /** Whether to hold the update back while the bot is running, since installing ends the app. */
        val holdWhileBotRunning: Boolean = true,
    )

    /** One downloadable file attached to a release. */
    data class ReleaseAsset(
        /** The file name, e.g. "v5.8.8-UmaAndroidAutomation-x86_64-release.apk". */
        val name: String,
        /** The file size in bytes. */
        val size: Long,
        /** The direct download link. */
        val url: String,
    )

    /** What the update dialog shows for one release. */
    data class UpdateInfo(
        /** The release version without the leading "v". */
        val latestVersion: String,
        /** The release page on GitHub. */
        val url: String,
        /** The author-written release notes with LF line endings. */
        val releaseNotes: String,
        /** The files attached to the release. */
        val assets: List<ReleaseAsset> = emptyList(),
    )

    /** What [checkForUpdate] decided for the latest release. */
    enum class Decision { SHOW, HOLD, UP_TO_DATE }

    /** Result of [checkForUpdate]. */
    data class CheckResult(
        /** What was done with the latest release. */
        val decision: Decision,
        /** The latest release version. */
        val version: String,
    )

    /**
     * Fetches one GitHub release off the main thread.
     *
     * @param path The path under the releases API, e.g. "latest" or "tags/v5.8.8".
     * @return The parsed release.
     * @throws IOException When GitHub cannot be reached, answers with an error, or the release is incomplete.
     */
    private suspend fun fetchRelease(path: String): UpdateInfo =
        withContext(Dispatchers.IO) {
            val connection = URL("https://api.github.com/repos/${config.owner}/${config.repo}/releases/$path").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("GitHub returned HTTP ${connection.responseCode}.")
                parseRelease(connection.inputStream.bufferedReader().use { it.readText() }, config.notesEndMarkers) ?: throw IOException("The GitHub release is missing its version.")
            } finally {
                connection.disconnect()
            }
        }

    /**
     * Fetches the latest release and opens the update dialog when it is newer and not held back. An update dialog that is already open is left
     * alone, so a download in progress is never interrupted.
     *
     * @return What was decided for the latest release.
     * @throws IOException When GitHub cannot be reached or the release cannot be read.
     */
    suspend fun checkForUpdate(): CheckResult {
        AppUpdateDialog.current?.takeIf { it.isActive }?.let { return CheckResult(Decision.SHOW, it.version) }
        AppUpdateInstaller.clearLeftovers(activity)
        val updateInfo = fetchRelease("latest")
        val decision = decide(updateInfo.latestVersion, config.currentVersion, config.holdWhileBotRunning && BotService.isRunning)
        if (decision == Decision.SHOW) AppUpdateDialog(activity, updateInfo, AppUpdateDialog.Mode.UPDATE_AVAILABLE, config).show()
        return CheckResult(decision, updateInfo.latestVersion)
    }

    /**
     * Shows the release notes of the installed version as a read-only changelog. Falls back to the latest release when the installed version has
     * no release, such as a local build. Network failures are ignored.
     */
    fun showCurrentChangelog() {
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            try {
                val updateInfo =
                    try {
                        fetchRelease("tags/v${config.currentVersion}")
                    } catch (_: IOException) {
                        fetchRelease("latest")
                    }
                AppUpdateDialog(activity, updateInfo, AppUpdateDialog.Mode.CURRENT_CHANGELOG, config).show()
            } catch (_: Exception) {
                // Silently ignore network or parsing failures.
            }
        }
    }
}
