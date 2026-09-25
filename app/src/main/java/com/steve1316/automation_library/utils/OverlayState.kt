package com.steve1316.automation_library.utils

import java.util.Locale

/** Colors used by the overlay orb and tray, as ARGB ints. */
internal object OverlayColors {
    /** Orb and tray fill, #141416 at 82% alpha. */
    val ORB_FILL: Int = 0xD1141416.toInt()

    /** Orb and tray edge, white at 14% alpha. */
    val ORB_STROKE: Int = 0x24FFFFFF

    /** Orb drop shadow. */
    val SHADOW: Int = 0x61000000

    /** Unfilled part of the ring, white at 16% alpha. */
    val RING_TRACK: Int = 0x29FFFFFF

    /** Running and finished. */
    val GREEN: Int = 0xFF34D399.toInt()

    /** Pausing and paused. */
    val AMBER: Int = 0xFFFBBF24.toInt()

    /** Stopped by the bot and crashed. */
    val RED: Int = 0xFFF87171.toInt()

    /** Spinning ring segment, white at 85% alpha. */
    val SPINNER: Int = 0xD9FFFFFF.toInt()

    /** Glyphs and tray headline. */
    val WHITE: Int = 0xFFFFFFFF.toInt()

    /** Dimmed glyph while pausing or stopping, white at 50% alpha. */
    val WHITE_DIM: Int = 0x80FFFFFF.toInt()

    /** Tray second line, white at 66% alpha. */
    val TRAY_SUBTEXT: Int = 0xA8FFFFFF.toInt()

    /** Pause button, white at 10% alpha. */
    val BUTTON_NEUTRAL: Int = 0x1AFFFFFF

    /** Resume and Start again buttons. */
    val BUTTON_GO: Int = 0xFF2F9E6B.toInt()

    /** Stop button. */
    val BUTTON_STOP: Int = 0xFFE5484D.toInt()
}

/** Overlay style picked in the consuming app's settings. */
enum class OverlayStyle {
    /** Tapping the orb while running opens a mini tray with the status, Pause, and Stop. */
    TRAY,

    /** Tapping the orb starts or stops the bot, like the original button. */
    SIMPLE,
    ;

    companion object {
        /**
         * Reads the stored setting value.
         *
         * @param value The stored `misc.overlayStyle` value.
         * @return `SIMPLE` for "simple" in any case, otherwise `TRAY`.
         */
        fun fromSetting(value: String): OverlayStyle = if (value.equals("simple", ignoreCase = true)) SIMPLE else TRAY
    }
}

/** What the overlay is showing. */
internal enum class OverlayVisual { READY, RUNNING, PAUSING, PAUSED, STOPPING, FINISHED, STOPPED }

/** What a tap on the orb does. */
internal enum class OverlayTapAction { START, STOP, RESUME, OPEN_TRAY, NONE }

/** A glyph drawn in the orb or on a tray button. */
internal enum class Glyph { PLAY, STOP, PAUSE, CHECK, ALERT }

/**
 * A button in the tray.
 *
 * @property glyph The glyph drawn on the button.
 * @property label The accessibility label.
 * @property color The button's fill color.
 */
internal enum class TrayButton(val glyph: Glyph, val label: String, val color: Int) {
    PAUSE(Glyph.PAUSE, "Pause", OverlayColors.BUTTON_NEUTRAL),
    RESUME(Glyph.PLAY, "Resume", OverlayColors.BUTTON_GO),
    STOP(Glyph.STOP, "Stop", OverlayColors.BUTTON_STOP),
    START(Glyph.PLAY, "Start again", OverlayColors.BUTTON_GO),
}

/** The ring drawn around the orb. */
internal data class OrbRing(
    /** Filled part of the ring, from 0 to 1. */
    val fraction: Float,
    /** Color of the filled part. */
    val color: Int,
    /** True to also draw the spinning segment. */
    val spinning: Boolean,
)

/** The tray's first line: the progress label plus an optional colored tag such as "PAUSED". */
internal data class TrayHeadline(
    /** Progress label such as "Turn 34/72" or "Quest 3/10". */
    val text: String,
    /** Tag shown after the label, or null for none. */
    val tag: String?,
    /** Color of the tag. */
    val tagColor: Int,
)

/** Rules that map the run state to what the overlay draws and does. Kept free of Android views so they can be unit tested. */
internal object OverlayStateLogic {
    /**
     * Picks what the overlay shows.
     *
     * @param isRunning True while a run is in progress.
     * @param isStopping True after the user asked to stop and before the run has ended.
     * @param pauseState The current pause state.
     * @param outcome How the last run ended, or null when there is none to show.
     * @return The visual state.
     */
    fun visualFor(isRunning: Boolean, isStopping: Boolean, pauseState: BotHold.PauseState, outcome: BotStatus.Outcome?): OverlayVisual =
        when {
            isStopping -> OverlayVisual.STOPPING
            isRunning && pauseState == BotHold.PauseState.PAUSED -> OverlayVisual.PAUSED
            isRunning && pauseState == BotHold.PauseState.REQUESTED -> OverlayVisual.PAUSING
            isRunning -> OverlayVisual.RUNNING
            outcome == BotStatus.Outcome.FINISHED -> OverlayVisual.FINISHED
            outcome == BotStatus.Outcome.STOPPED_BY_BOT || outcome == BotStatus.Outcome.CRASHED -> OverlayVisual.STOPPED
            else -> OverlayVisual.READY
        }

    /**
     * Picks what a tap on the orb does.
     *
     * @param style The overlay style.
     * @param visual What the overlay is showing.
     * @return The tap action.
     */
    fun tapActionFor(style: OverlayStyle, visual: OverlayVisual): OverlayTapAction {
        val tray = style == OverlayStyle.TRAY
        return when (visual) {
            OverlayVisual.READY -> OverlayTapAction.START
            OverlayVisual.STOPPING -> OverlayTapAction.NONE
            OverlayVisual.RUNNING -> if (tray) OverlayTapAction.OPEN_TRAY else OverlayTapAction.STOP
            OverlayVisual.PAUSING, OverlayVisual.PAUSED -> if (tray) OverlayTapAction.OPEN_TRAY else OverlayTapAction.RESUME
            OverlayVisual.FINISHED, OverlayVisual.STOPPED -> if (tray) OverlayTapAction.OPEN_TRAY else OverlayTapAction.START
        }
    }

    /**
     * Picks the tray's buttons.
     *
     * @param visual What the overlay is showing.
     * @param canPause True when the run has reached a safe point, so a pause can land.
     * @return The buttons in order, or an empty list when the tray should not open.
     */
    fun trayButtonsFor(visual: OverlayVisual, canPause: Boolean): List<TrayButton> =
        when (visual) {
            OverlayVisual.RUNNING -> if (canPause) listOf(TrayButton.PAUSE, TrayButton.STOP) else listOf(TrayButton.STOP)
            OverlayVisual.PAUSING, OverlayVisual.PAUSED -> listOf(TrayButton.RESUME, TrayButton.STOP)
            OverlayVisual.FINISHED, OverlayVisual.STOPPED -> listOf(TrayButton.START)
            OverlayVisual.READY, OverlayVisual.STOPPING -> emptyList()
        }

    /**
     * Picks the ring drawn around the orb.
     *
     * @param visual What the overlay is showing.
     * @param snapshot The current run status.
     * @return The ring.
     */
    fun ringFor(visual: OverlayVisual, snapshot: BotStatus.Snapshot): OrbRing =
        when (visual) {
            OverlayVisual.READY -> OrbRing(0f, OverlayColors.GREEN, false)
            OverlayVisual.RUNNING -> OrbRing(snapshot.fraction, OverlayColors.GREEN, false)
            OverlayVisual.PAUSING -> OrbRing(snapshot.fraction, OverlayColors.AMBER, true)
            OverlayVisual.PAUSED -> OrbRing(snapshot.fraction, OverlayColors.AMBER, false)
            OverlayVisual.STOPPING -> OrbRing(0f, OverlayColors.GREEN, true)
            OverlayVisual.FINISHED -> OrbRing(1f, OverlayColors.GREEN, false)
            OverlayVisual.STOPPED -> OrbRing(1f, OverlayColors.RED, false)
        }

    /**
     * Picks what goes in the middle of the orb. The step number shows only in tray style while running with a step from 1 to 999, otherwise a glyph.
     *
     * @param style The overlay style.
     * @param visual What the overlay is showing.
     * @param snapshot The current run status.
     * @return The glyph to draw, or null to draw the current step number instead.
     */
    fun orbCenterFor(style: OverlayStyle, visual: OverlayVisual, snapshot: BotStatus.Snapshot): Glyph? =
        when (visual) {
            OverlayVisual.READY -> Glyph.PLAY
            OverlayVisual.RUNNING -> if (style == OverlayStyle.TRAY && snapshot.current in 1..999) null else Glyph.STOP
            OverlayVisual.PAUSING, OverlayVisual.PAUSED -> Glyph.PAUSE
            OverlayVisual.STOPPING -> Glyph.STOP
            OverlayVisual.FINISHED -> Glyph.CHECK
            OverlayVisual.STOPPED -> Glyph.ALERT
        }

    /**
     * Picks the color of the orb's center glyph.
     *
     * @param visual What the overlay is showing.
     * @return An ARGB color.
     */
    fun glyphColorFor(visual: OverlayVisual): Int =
        when (visual) {
            OverlayVisual.PAUSED -> OverlayColors.AMBER
            OverlayVisual.PAUSING, OverlayVisual.STOPPING -> OverlayColors.WHITE_DIM
            else -> OverlayColors.WHITE
        }

    /**
     * Whether to draw the breathing dot.
     *
     * @param visual What the overlay is showing.
     * @return True only while running.
     */
    fun showsBreathingDot(visual: OverlayVisual): Boolean = visual == OverlayVisual.RUNNING

    /**
     * Builds the tray's first line.
     *
     * @param visual What the overlay is showing.
     * @param snapshot The current run status.
     * @return The label and its optional tag.
     */
    fun headlineFor(visual: OverlayVisual, snapshot: BotStatus.Snapshot): TrayHeadline {
        val text = snapshot.label.ifEmpty { "Automation" }
        return when (visual) {
            OverlayVisual.PAUSING -> TrayHeadline(text, "PAUSING", OverlayColors.AMBER)
            OverlayVisual.PAUSED -> TrayHeadline(text, "PAUSED", OverlayColors.AMBER)
            OverlayVisual.FINISHED -> TrayHeadline(text, "DONE", OverlayColors.GREEN)
            OverlayVisual.STOPPED -> TrayHeadline(text, "STOPPED", OverlayColors.RED)
            else -> TrayHeadline(text, null, OverlayColors.WHITE)
        }
    }

    /**
     * Builds the tray's second line: the running time, then the pause reason, the last action, or the end reason.
     *
     * @param visual What the overlay is showing.
     * @param snapshot The current run status.
     * @param pauseReason Why the bot is pausing, or empty when the user asked for it. Shown only while pausing or paused.
     * @return The line, such as "1:12:05 - Trained Speed".
     */
    fun trayDetailFor(visual: OverlayVisual, snapshot: BotStatus.Snapshot, pauseReason: String = ""): String {
        val time = formatElapsed(snapshot.elapsedMs)
        val extra =
            when {
                visual == OverlayVisual.FINISHED || visual == OverlayVisual.STOPPED -> snapshot.reason
                (visual == OverlayVisual.PAUSED || visual == OverlayVisual.PAUSING) && pauseReason.isNotEmpty() -> pauseReason
                else -> snapshot.detail
            }
        return if (extra.isEmpty()) time else "$time · $extra"
    }

    /**
     * Where the tray's top edge goes so the tray is vertically centered on the orb. WindowManager keeps the orb window on screen, so a drag
     * past the top or bottom edge leaves the stored y outside the drawn position. The orb's drawn position is used instead.
     *
     * @param orbWindowY The orb window's stored y, which can be past an edge after a drag.
     * @param screenHeight The screen height in pixels.
     * @param buttonSizePx The orb window's size, the orb plus its shadow pad on both sides.
     * @param shadowPadPx The shadow pad on each side of the orb.
     * @param trayHeight The tray's measured height.
     * @return The tray window's y.
     */
    fun trayTopFor(orbWindowY: Int, screenHeight: Int, buttonSizePx: Int, shadowPadPx: Int, trayHeight: Int): Int {
        val drawnY = orbWindowY.coerceIn(0, (screenHeight - buttonSizePx).coerceAtLeast(0))
        val orbSizePx = buttonSizePx - shadowPadPx * 2
        return drawnY + shadowPadPx + (orbSizePx - trayHeight) / 2
    }

    /**
     * Formats a duration as h:mm:ss, or m:ss under an hour.
     *
     * @param ms The duration in milliseconds.
     * @return The formatted duration.
     */
    fun formatElapsed(ms: Long): String {
        val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds) else String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    /**
     * Builds the orb's accessibility description, which says what a tap does.
     *
     * @param style The overlay style.
     * @param visual What the overlay is showing.
     * @return The description.
     */
    fun describe(style: OverlayStyle, visual: OverlayVisual): String {
        val tray = style == OverlayStyle.TRAY
        return when (visual) {
            OverlayVisual.READY -> "Start automation"
            OverlayVisual.RUNNING -> if (tray) "Automation running. Tap for controls" else "Stop automation"
            OverlayVisual.PAUSING -> if (tray) "Automation pausing. Tap for controls" else "Resume automation"
            OverlayVisual.PAUSED -> if (tray) "Automation paused. Tap for controls" else "Resume automation"
            OverlayVisual.STOPPING -> "Stopping automation"
            OverlayVisual.FINISHED -> if (tray) "Automation finished. Tap for the result" else "Start automation"
            OverlayVisual.STOPPED -> if (tray) "Automation stopped. Tap for the reason" else "Start automation"
        }
    }
}
