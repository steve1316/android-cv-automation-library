package com.steve1316.automation_library.utils

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * The report of how the last run ended, which the app's Home screen shows.
 *
 * `BotService` builds it at cleanup from `BotStatus`, saves it as `last_run.json` in the app's private files, and sends it to the app. The consuming app can
 * add its own run summary through `setSummaryProvider()`.
 */
object RunReport {
    /** Version of the report's JSON shape. A saved file with any other version is ignored. */
    const val SCHEMA_VERSION = 1

    private const val TAG = "RunReport"
    private const val FILE_NAME = "last_run.json"

    @Volatile private var summaryProvider: (() -> JSONObject?)? = null

    /**
     * Sets the hook that adds the app's run summary to the report.
     *
     * @param provider Returns the summary, or null when there is none. Pass null to remove the hook.
     */
    fun setSummaryProvider(provider: (() -> JSONObject?)?) {
        summaryProvider = provider
    }

    /**
     * Builds the report from the current run status.
     *
     * @param logFile The saved log file name, or null when the log was not saved.
     * @param endedAtMs Wall-clock time the run ended.
     * @return The report JSON.
     */
    fun build(logFile: String?, endedAtMs: Long = System.currentTimeMillis()): JSONObject {
        val status = BotStatus.snapshot()
        val report =
            JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("outcome", (status.outcome ?: BotStatus.Outcome.FINISHED).name)
                .put("reason", status.reason)
                .put("turn", status.current)
                .put("totalTurns", status.total)
                .put("runtimeMs", status.elapsedMs)
                .put("endedAt", endedAtMs)
                .put("logFile", logFile ?: "")
        BotStatus.lastError()?.let { error ->
            report.put(
                "error",
                JSONObject()
                    .put("className", error.className)
                    .put("message", error.message)
                    .put("frames", JSONArray(error.frames)),
            )
        }
        // A summary that fails to build must never cost the rest of the report.
        val summary =
            try {
                summaryProvider?.invoke()
            } catch (t: Throwable) {
                Log.w(TAG, "The run summary could not be built: $t")
                null
            }
        if (summary != null) report.put("summary", summary)
        return report
    }

    /**
     * Saves the report, replacing the last one.
     *
     * @param dir The app's private files folder.
     * @param report The report from `build()`.
     */
    fun save(dir: File, report: JSONObject) {
        File(dir, FILE_NAME).writeText(report.toString())
    }

    /**
     * Reads the saved report.
     *
     * @param dir The app's private files folder.
     * @return The report JSON text, or null when there is none or it is unreadable or from another version.
     */
    fun load(dir: File): String? {
        val file = File(dir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            if (JSONObject(text).optInt("schemaVersion") == SCHEMA_VERSION) text else null
        } catch (e: JSONException) {
            null
        }
    }

    /**
     * Deletes the saved report.
     *
     * @param dir The app's private files folder.
     */
    fun clear(dir: File) {
        File(dir, FILE_NAME).delete()
    }
}
