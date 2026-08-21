package com.steve1316.automation_library.utils

/** Which pause control the status notification shows. */
internal enum class PauseAction {
    /** No pause control. */
    NONE,

    /** A Pause action. */
    PAUSE,

    /** A Resume action, which also cancels a pause that has not landed yet. */
    RESUME,
}

/** Text and controls of one status notification. Built by `StatusContentBuilder` so the wording can be unit tested without Android. */
internal data class StatusContent(
    /** Notification title. */
    val title: String,
    /** Notification body text. */
    val text: String,
    /** True to post on the alerting channel with a short banner, false to post silently. */
    val alert: Boolean = false,
    /** Which pause control to show. */
    val pauseAction: PauseAction = PauseAction.NONE,
    /** True to show the Stop action. */
    val showStop: Boolean = true,
    /** Progress bar as current to max, or null for no bar. */
    val progress: Pair<Int, Int>? = null,
    /** True to show a ticking running-time chronometer. */
    val chronometer: Boolean = false,
    /** Frozen running time shown while paused, or null. */
    val subText: String? = null,
)

/** Builds the status notification's content for each stage of a run. */
internal object StatusContentBuilder {
    /**
     * Content shown while screen capture is on and no run has started yet.
     *
     * @return The ready content.
     */
    fun ready(): StatusContent = StatusContent(title = "Ready", text = "Tap the overlay to start")

    /**
     * Content shown while a run is in progress.
     *
     * @param snapshot The current run status.
     * @param pauseState The current pause state.
     * @param canPause True when the run has reached a safe point, so a pause can land. Without one, no Pause action is shown.
     * @return The running content.
     */
    fun running(snapshot: BotStatus.Snapshot, pauseState: BotHold.PauseState, canPause: Boolean): StatusContent {
        val label = snapshot.label.ifEmpty { "Running" }
        val progress = if (snapshot.total > 0) minOf(snapshot.current, snapshot.total) to snapshot.total else null
        return when (pauseState) {
            BotHold.PauseState.NONE ->
                StatusContent(
                    title = label,
                    text = snapshot.detail.ifEmpty { "Automation is running" },
                    pauseAction = if (canPause) PauseAction.PAUSE else PauseAction.NONE,
                    progress = progress,
                    chronometer = true,
                )
            BotHold.PauseState.REQUESTED ->
                StatusContent(title = "$label · Pausing", text = "Pausing after the current step", pauseAction = PauseAction.RESUME, progress = progress, chronometer = true)
            BotHold.PauseState.PAUSED ->
                StatusContent(
                    title = "$label · Paused",
                    text = "Tap Resume when you are back in the game",
                    pauseAction = PauseAction.RESUME,
                    progress = progress,
                    subText = OverlayStateLogic.formatElapsed(snapshot.elapsedMs),
                )
        }
    }

    /**
     * Content shown once a run has ended. Only a stop the user asked for is silent.
     *
     * @param snapshot The run status, with its outcome set.
     * @return The ended content.
     */
    fun ended(snapshot: BotStatus.Snapshot): StatusContent {
        val time = OverlayStateLogic.formatElapsed(snapshot.elapsedMs)
        val at = if (snapshot.label.isNotEmpty()) " at ${snapshot.label}" else ""
        val (title, text, alert) =
            when (snapshot.outcome) {
                BotStatus.Outcome.STOPPED_BY_BOT -> Triple("Stopped$at", "${sentence(snapshot.reason.ifEmpty { "The bot stopped" })} Ran for $time", true)
                BotStatus.Outcome.STOPPED_BY_USER -> Triple("Stopped$at", "You stopped the bot. Ran for $time", false)
                BotStatus.Outcome.CRASHED -> Triple("Bot crashed$at", "${sentence(snapshot.reason.ifEmpty { "The bot crashed" })} Open the app for details.", true)
                BotStatus.Outcome.FINISHED, null -> Triple("Finished", "${sentence(snapshot.reason.ifEmpty { "Run ended" })} Ran for $time", true)
            }
        return StatusContent(title = title, text = text, alert = alert)
    }

    /**
     * Ends text with a period unless it already ends with sentence punctuation.
     *
     * @param text The text to finish.
     * @return The trimmed text as a full sentence, or an empty string for empty input.
     */
    fun sentence(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.isEmpty() || trimmed.last() in ".!?") trimmed else "$trimmed."
    }
}
