package com.steve1316.automation_library.utils

import android.content.Context
import android.content.Intent

/**
 * The game the app automates. An app that calls `configure()` gets a check before each run: the run ends before the app's code is handed
 * control when the game is not installed or another app is in front, so no tap or swipe ever lands outside the game. Apps that never call it
 * skip the check.
 */
object GameTarget {
    /** Reason a run ends with when another app is in front as it starts. */
    const val NOT_ON_SCREEN_REASON = "The game is not on screen, so the bot did not start"

    /** The game's display name, such as "Umamusume Pretty Derby". */
    @Volatile
    private var name: String = ""

    /** The game's package names. Empty means no game is configured. */
    @Volatile
    private var packages: Set<String> = emptySet()

    /**
     * Sets the game the app automates. Call it once when the app starts.
     *
     * @param name The game's display name.
     * @param packages The game's package names.
     */
    fun configure(name: String, packages: Set<String>) {
        this.name = name
        this.packages = packages
    }

    /**
     * Finds the intent that opens the game.
     *
     * @param context Any context, used for the package manager.
     * @return The launch intent of an installed game package, or null when none is installed.
     */
    private fun launchIntent(context: Context): Intent? = packages.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }

    /**
     * Opens the game in a new task.
     *
     * @param context Any context.
     * @return True when the game was opened, false when it is not installed.
     */
    fun launch(context: Context): Boolean {
        val intent = launchIntent(context) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /**
     * The message shown when the configured game is not installed.
     *
     * @return The message, such as "Umamusume Pretty Derby (com.cygames.umamusume) is not installed".
     */
    fun notInstalledMessage(): String = notInstalledMessage(name, packages)

    /**
     * Whether another app is known to be in front instead of the game. False when no game is configured or the app in front is unknown.
     *
     * @return True when the app in front is known and is not the game.
     */
    fun otherAppInFront(): Boolean = packages.isNotEmpty() && otherAppInFront(packages, FocusWatch.foregroundPackage)

    /**
     * Checks whether a run may start, from the configured game, whether it is installed, and the app in front.
     *
     * @param context Any context, used for the package manager.
     * @return Why the run must not start, or null when it may.
     */
    fun startBlockReason(context: Context): String? = startBlockReason(name, packages, launchIntent(context) != null, FocusWatch.foregroundPackage)

    /**
     * Builds the not-installed message.
     *
     * @param name The game's display name.
     * @param packages The game's package names.
     * @return The message naming the game and its packages.
     */
    private fun notInstalledMessage(name: String, packages: Set<String>): String = "$name (${packages.joinToString(", ")}) is not installed"

    /**
     * Decides whether a run may start. A missing game blocks it, and so does another app in front. An unknown app in front lets it start, since
     * nothing has been seen yet to say the game is not there.
     *
     * @param name The game's display name.
     * @param packages The game's package names, or empty when no game is configured.
     * @param installed Whether any of the game's packages is installed.
     * @param foreground The app in front, or null when unknown.
     * @return Why the run must not start, or null when it may.
     */
    internal fun startBlockReason(name: String, packages: Set<String>, installed: Boolean, foreground: String?): String? =
        when {
            packages.isEmpty() -> null
            !installed -> notInstalledMessage(name, packages)
            otherAppInFront(packages, foreground) -> NOT_ON_SCREEN_REASON
            else -> null
        }

    /**
     * Whether the app in front is known and is not the game.
     *
     * @param packages The game's package names.
     * @param foreground The app in front, or null when unknown.
     * @return True when another app is known to be in front.
     */
    internal fun otherAppInFront(packages: Set<String>, foreground: String?): Boolean = foreground != null && foreground !in packages
}
