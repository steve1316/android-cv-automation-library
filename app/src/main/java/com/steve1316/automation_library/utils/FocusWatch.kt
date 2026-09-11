package com.steve1316.automation_library.utils

/** What one window change means for a run. */
internal enum class FocusVerdict {
    /** Nothing to act on, such as this app's overlay windows, a keyboard, or a heads-up banner over the game. */
    IGNORE,

    /** The game is in front. */
    GAME_IN_FRONT,

    /** Something other than the game took the screen. */
    GAME_LEFT,
}

/** One `TYPE_WINDOW_STATE_CHANGED` event as the accessibility service saw it. */
internal data class WindowChange(
    /** Package that sent the event, or null when the event carried none. */
    val packageName: String?,
    /** Class name of the window or view that changed, or null. */
    val className: String?,
    /** Package of the window that has input focus right after the event, or null when it could not be read. */
    val activePackage: String?,
)

/** The package rules a window change is judged by. */
internal data class FocusRules(
    /** Package names of the game. */
    val gamePackages: Set<String>,
    /** This app's own package. Its screen in front covers the game and counts as leaving it, but its toasts and overlay windows do not. */
    val ownPackage: String,
    /** Packages of the enabled keyboards. A keyboard opening over the game is not leaving it. */
    val inputMethodPackages: Set<String> = emptySet(),
    /** The System UI package, which owns the notification shade, quick settings, and heads-up banners. */
    val systemUiPackage: String = FocusWatch.SYSTEM_UI_PACKAGE,
    /** System UI window classes that never take the screen from the game, such as heads-up banners. Filled from the emulator truth table. */
    val systemUiIgnoredClasses: Set<String> = FocusWatch.SYSTEM_UI_IGNORED_CLASSES,
)

/**
 * Tracks which app is in front and pauses an opted-in run when the game leaves the screen. `MyAccessibilityService` feeds it every window
 * change. The rules are pure functions, so they are checked against the emulator truth table in unit tests.
 */
object FocusWatch {
    /** The System UI package on stock Android and the emulator. */
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /** System UI window classes that never take the screen from the game. Empty because no heads-up banner was seen on the emulator. */
    internal val SYSTEM_UI_IGNORED_CLASSES: Set<String> = emptySet()

    /** The last app seen in front, ignoring System UI, keyboards, and this app's toasts and overlays. Null until the first such window change. */
    @Volatile
    var foregroundPackage: String? = null
        private set

    /**
     * Judges one window change. The game coming to the front is `GAME_IN_FRONT`. This app's own screen in front (its active window) is
     * `GAME_LEFT`. Its toasts, starting window, and overlays never take focus, so they are `IGNORE`, as are keyboards, events without a package,
     * and System UI windows that leave the game focused (heads-up banners). Everything else, including the shade, is `GAME_LEFT`.
     *
     * @param change The window change.
     * @param rules The package rules.
     * @return What the change means for the run.
     */
    internal fun classify(change: WindowChange, rules: FocusRules): FocusVerdict {
        val pkg = change.packageName
        val className = change.className
        val activePackage = change.activePackage
        return when {
            pkg.isNullOrEmpty() -> FocusVerdict.IGNORE
            pkg == rules.ownPackage -> if (activePackage == rules.ownPackage) FocusVerdict.GAME_LEFT else FocusVerdict.IGNORE
            pkg in rules.inputMethodPackages -> FocusVerdict.IGNORE
            pkg in rules.gamePackages -> FocusVerdict.GAME_IN_FRONT
            pkg == rules.systemUiPackage && className != null && className in rules.systemUiIgnoredClasses -> FocusVerdict.IGNORE
            pkg == rules.systemUiPackage && activePackage != null && activePackage in rules.gamePackages -> FocusVerdict.IGNORE
            else -> FocusVerdict.GAME_LEFT
        }
    }

    /**
     * Works out the app in front after a window change. This app's event counts only when its own window is focused, otherwise it leaves
     * `current` unchanged. System UI, keyboards, and events without a package also leave it unchanged.
     *
     * @param change The window change.
     * @param rules The package rules.
     * @param current The app in front before the change, or null when unknown.
     * @return The app in front after the change, or null when still unknown.
     */
    internal fun nextForeground(change: WindowChange, rules: FocusRules, current: String?): String? {
        val pkg = change.packageName
        if (pkg == rules.ownPackage) return if (change.activePackage == rules.ownPackage) pkg else current
        if (pkg.isNullOrEmpty() || pkg == rules.systemUiPackage || pkg in rules.inputMethodPackages) return current
        return pkg
    }

    /**
     * Handles one window change from the accessibility service: updates `foregroundPackage`, and pauses an opted-in run when the game left.
     *
     * @param change The window change.
     * @param ownPackage This app's package.
     * @param inputMethodPackages Packages of the enabled keyboards.
     */
    internal fun onWindowChange(change: WindowChange, ownPackage: String, inputMethodPackages: Set<String>) {
        val rules = FocusRules(gamePackages = BotHold.gamePackages, ownPackage = ownPackage, inputMethodPackages = inputMethodPackages)
        foregroundPackage = nextForeground(change, rules, foregroundPackage)
        if (classify(change, rules) == FocusVerdict.GAME_LEFT && BotHold.pauseForFocusLoss()) {
            MessageLog.i(message = "[PAUSE] The game left the screen (${change.packageName}). Pausing until you tap Resume.")
        }
    }
}
