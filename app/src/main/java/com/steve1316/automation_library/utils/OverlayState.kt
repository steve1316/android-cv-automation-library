package com.steve1316.automation_library.utils

import java.util.Locale

/** Colors used by the overlay orb and tray, as ARGB ints. */
internal object OverlayColors {
    /** Orb and tray fill, #141416 at 82% alpha. */
    val ORB_FILL: Int = 0xD1141416.toInt()

    /** Orb and tray hairline edge, white at 10% alpha. */
    val ORB_STROKE: Int = 0x1AFFFFFF

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

    /** Tray second line, white at 58% alpha. */
    val TRAY_SUBTEXT: Int = 0x94FFFFFF.toInt()

    /** Top of the orb and tray glass gradient, #2C2C34 at 88% alpha. */
    val GLASS_TOP: Int = 0xE02C2C34.toInt()

    /** Bottom of the orb and tray glass gradient, #0E0E12 at 88% alpha. */
    val GLASS_BOTTOM: Int = 0xE00E0E12.toInt()

    /** Thin highlight along the top inside edge of the glass, white at 18% alpha. */
    val GLASS_HIGHLIGHT: Int = 0x2EFFFFFF

    /** Tight contact shadow under the glass, black at 40% alpha. */
    val SHADOW_TIGHT: Int = 0x66000000

    /** Soft wide shadow under the glass, black at 45% alpha. */
    val SHADOW_SOFT: Int = 0x73000000

    /** Unfilled part of the tray's progress bar, white at 12% alpha. */
    val PROGRESS_TRACK: Int = 0x1FFFFFFF

    /** Top of the Pause button gradient, white at 16% alpha. */
    val BUTTON_NEUTRAL_TOP: Int = 0x29FFFFFF

    /** Bottom of the Pause button gradient, white at 7% alpha. */
    val BUTTON_NEUTRAL_BOTTOM: Int = 0x12FFFFFF

    /** Highlight along the top of every tray button, white at 14% alpha. */
    val BUTTON_HIGHLIGHT: Int = 0x24FFFFFF

    /** Top of the Stop button gradient. */
    val BUTTON_STOP_TOP: Int = 0xFFF0605F.toInt()

    /** Bottom of the Stop button gradient. */
    val BUTTON_STOP_BOTTOM: Int = 0xFFD63C43.toInt()

    /** Top of the Resume and Start again button gradient. */
    val BUTTON_GO_TOP: Int = 0xFF3CB57D.toInt()

    /** Bottom of the Resume and Start again button gradient. */
    val BUTTON_GO_BOTTOM: Int = 0xFF278A5C.toInt()

    /**
     * The faint background of a tray tag: the tag's own color at 16% alpha.
     *
     * @param color The tag's text color.
     * @return The same color at 16% alpha.
     */
    fun tintOf(color: Int): Int = (color and 0x00FFFFFF) or 0x29000000
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
 * @property topColor The top of the button's gradient fill.
 * @property bottomColor The bottom of the button's gradient fill.
 */
internal enum class TrayButton(val glyph: Glyph, val label: String, val topColor: Int, val bottomColor: Int) {
    PAUSE(Glyph.PAUSE, "Pause", OverlayColors.BUTTON_NEUTRAL_TOP, OverlayColors.BUTTON_NEUTRAL_BOTTOM),
    RESUME(Glyph.PLAY, "Resume", OverlayColors.BUTTON_GO_TOP, OverlayColors.BUTTON_GO_BOTTOM),
    STOP(Glyph.STOP, "Stop", OverlayColors.BUTTON_STOP_TOP, OverlayColors.BUTTON_STOP_BOTTOM),
    START(Glyph.PLAY, "Start again", OverlayColors.BUTTON_GO_TOP, OverlayColors.BUTTON_GO_BOTTOM),
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

/** How the tray view is drawn at rest or at the start or end of an animation. */
internal data class TrayPose(
    /** Horizontal scale around the unfurl pivot. */
    val scaleX: Float,
    /** Alpha of the whole tray, pill included. */
    val alpha: Float,
    /** Alpha of the tray's text and buttons. */
    val contentAlpha: Float,
)

/** Timings and scales for the overlay's motion, in one place so the views and tests agree. */
internal object OverlayMotion {
    /** How long the tray takes to unfurl out of the orb, including its overshoot. */
    const val TRAY_OPEN_MS = 260L

    /** How long the tray takes to fade in while it unfurls. */
    const val TRAY_FADE_IN_MS = 120L

    /** Delay before the tray's text and buttons fade in, so they never look squashed mid-stretch. */
    const val CONTENT_FADE_IN_DELAY_MS = 110L

    /** How long the tray's text and buttons take to fade in. */
    const val CONTENT_FADE_IN_MS = 140L

    /** How long the tray takes to shrink back into the orb and fade out. The bot is released when it ends. */
    const val TRAY_CLOSE_MS = 120L

    /** How long the tray's text and buttons take to fade out when it closes. */
    const val CONTENT_FADE_OUT_MS = 60L

    /** The tray's horizontal scale at the start of opening and the end of closing. */
    const val UNFURL_START_SCALE = 0.35f

    /** The orb's scale while it is pressed. */
    const val PRESS_SCALE = 0.92f

    /** How long the orb takes to dip and come back when pressed. */
    const val PRESS_MS = 120L

    /** How long the ring takes to ease to a new progress value. */
    const val RING_EASE_MS = 400L

    /** How long the ring and glyph take to cross-fade to a new state color. */
    const val COLOR_FADE_MS = 250L

    /** A tray button's alpha while it is pressed. */
    const val PRESSED_BUTTON_ALPHA = 0.75f

    /** The tray fully open. */
    val OPEN_POSE = TrayPose(1f, 1f, 1f)

    /**
     * The tray closed: invisible at its unfurl start. A closed tray rests here, since its hidden window keeps the last frame drawn and shows
     * it for a moment on the next open.
     */
    val HIDDEN_POSE = TrayPose(UNFURL_START_SCALE, 0f, 0f)
}

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
     * @param isHeld True while the tray is open on a running bot, which waits until the tray closes.
     * @return The label and its optional tag.
     */
    fun headlineFor(visual: OverlayVisual, snapshot: BotStatus.Snapshot, isHeld: Boolean = false): TrayHeadline {
        val text = snapshot.label.ifEmpty { "Automation" }
        return when (visual) {
            OverlayVisual.RUNNING -> if (isHeld) TrayHeadline(text, "HELD", OverlayColors.AMBER) else TrayHeadline(text, null, OverlayColors.WHITE)
            OverlayVisual.PAUSING -> TrayHeadline(text, "PAUSING", OverlayColors.AMBER)
            OverlayVisual.PAUSED -> TrayHeadline(text, "PAUSED", OverlayColors.AMBER)
            OverlayVisual.FINISHED -> TrayHeadline(text, "DONE", OverlayColors.GREEN)
            OverlayVisual.STOPPED -> TrayHeadline(text, "STOPPED", OverlayColors.RED)
            else -> TrayHeadline(text, null, OverlayColors.WHITE)
        }
    }

    /**
     * Builds the tray's second line: the running time, then the pause reason, the last action, or the end reason. While the tray holds a
     * running bot it says so instead.
     *
     * @param visual What the overlay is showing.
     * @param snapshot The current run status.
     * @param pauseReason Why the bot is pausing, or empty when the user asked for it. Shown only while pausing or paused.
     * @param isHeld True while the tray is open on a running bot, which waits until the tray closes.
     * @return The line, such as "1:12:05 - Trained Speed".
     */
    fun trayDetailFor(visual: OverlayVisual, snapshot: BotStatus.Snapshot, pauseReason: String = "", isHeld: Boolean = false): String {
        if (isHeld && visual == OverlayVisual.RUNNING) return "Waiting while this is open"
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
     * Whether the tray opens to the right of the orb, toward the middle of the screen. Uses the orb's drawn position, since a drag past an
     * edge leaves the stored x outside it.
     *
     * @param orbWindowX The orb window's stored x.
     * @param screenWidth The screen width in pixels.
     * @param buttonSizePx The orb window's size, the orb plus its shadow pad on both sides.
     * @return True to open to the right, false to open to the left.
     */
    fun trayOpensRight(orbWindowX: Int, screenWidth: Int, buttonSizePx: Int): Boolean {
        val drawnX = orbWindowX.coerceIn(0, (screenWidth - buttonSizePx).coerceAtLeast(0))
        return drawnX + buttonSizePx / 2 < screenWidth / 2
    }

    /**
     * Where the tray window's left edge goes so its visible pill sits `gapPx` from the orb on the side facing the middle of the screen. The
     * tray window carries `trayPadPx` of shadow room on each side, which may overlap the gap. The result is kept on screen.
     *
     * @param orbWindowX The orb window's stored x, which can be past an edge after a drag.
     * @param screenWidth The screen width in pixels.
     * @param buttonSizePx The orb window's size, the orb plus its shadow pad on both sides.
     * @param shadowPadPx The orb's shadow pad on each side.
     * @param gapPx The space between the orb and the tray's pill.
     * @param trayWidth The tray window's measured width, shadow room included.
     * @param trayPadPx The tray window's shadow room on each side.
     * @return The tray window's x.
     */
    fun trayLeftFor(orbWindowX: Int, screenWidth: Int, buttonSizePx: Int, shadowPadPx: Int, gapPx: Int, trayWidth: Int, trayPadPx: Int): Int {
        val drawnX = orbWindowX.coerceIn(0, (screenWidth - buttonSizePx).coerceAtLeast(0))
        val orbLeft = drawnX + shadowPadPx
        val orbRight = orbLeft + buttonSizePx - shadowPadPx * 2
        val x = if (trayOpensRight(orbWindowX, screenWidth, buttonSizePx)) orbRight + gapPx - trayPadPx else orbLeft - gapPx - trayWidth + trayPadPx
        return x.coerceIn(0, (screenWidth - trayWidth).coerceAtLeast(0))
    }

    /**
     * The x the tray scales around while it unfurls: the edge of its pill that faces the orb.
     *
     * @param opensRight True when the tray opens to the right of the orb.
     * @param trayPadPx The tray window's shadow room on each side.
     * @param trayWidth The tray window's width, shadow room included.
     * @return The pivot x within the tray view.
     */
    fun unfurlPivotX(opensRight: Boolean, trayPadPx: Int, trayWidth: Int): Float = if (opensRight) trayPadPx.toFloat() else (trayWidth - trayPadPx).toFloat()

    /**
     * The room around the orb and the tray for their shadows. It grows with the orb, since the shadows are drawn in design units.
     *
     * @param orbSizeDp The orb's diameter in dp.
     * @return The shadow room on each side, in dp.
     */
    fun shadowPadDpFor(orbSizeDp: Float): Float = orbSizeDp * OverlayOrbView.SHADOW_PAD_UNITS / OverlayOrbView.BASE_SIZE_DP

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
