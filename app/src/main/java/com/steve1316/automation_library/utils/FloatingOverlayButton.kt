package com.steve1316.automation_library.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.steve1316.automation_library.data.SharedData
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Configuration for overlay features.
 */
object OverlayConfig {
    const val ENABLE_GUIDANCE_OVERLAYS = true
    const val ENABLE_DISMISS_DRAG = true
}

/**
 * Helper to convert dp to pixels using SharedData density or system density.
 *
 * @param dp The dp value to convert.
 * @return The pixel value.
 */
private fun Context.dpToPx(dp: Float): Int {
    val density = if (SharedData.displayDensity > 0F) SharedData.displayDensity else this.resources.displayMetrics.density
    return (dp * density).roundToInt()
}

/**
 * Shows or hides an overlay window by changing its window alpha instead of its root view visibility.
 *
 * Hiding the root view makes WindowManager destroy the window's surface, so every show had to allocate and draw a new surface on the main thread.
 * A window at zero alpha keeps its surface, is skipped by the compositor, and is ignored by the untrusted-touch filter as long as it is not touchable.
 *
 * @param windowManager The WindowManager the view was added to.
 * @param view The root view of the overlay window.
 * @param params The layout params the view was added with.
 * @param shown True to show the window, false to hide it.
 */
private fun setOverlayWindowShown(windowManager: WindowManager, view: View, params: WindowManager.LayoutParams, shown: Boolean) {
    val alpha = if (shown) 1f else 0f
    if (params.alpha == alpha) return
    params.alpha = alpha
    // The view may already have been removed by cleanup.
    runCatching { windowManager.updateViewLayout(view, params) }
}

/**
 * Helper to get the device's notch (display cutout) height programmatically.
 * Returns 0 if there is no notch or on older Android versions.
 *
 * @param windowManager The WindowManager instance.
 * @return The notch height in pixels, or 0 if not applicable.
 */
private fun getNotchHeight(windowManager: WindowManager): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // Android R (API 30) and above: use WindowMetrics.
        val windowInsets = windowManager.currentWindowMetrics.windowInsets
        val displayCutout = windowInsets.displayCutout
        displayCutout?.safeInsetTop ?: 0
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Android Q (API 29): use Display.getCutout().
        @Suppress("DEPRECATION")
        val displayCutout = windowManager.defaultDisplay.cutout
        displayCutout?.safeInsetTop ?: 0
    } else {
        // Android P and below: no reliable way to get cutout from a Service context.
        0
    }
}

/**
 * Gets the screen width, preferring the captured display size over the system metrics.
 *
 * @return The width in pixels.
 */
private fun Context.screenWidthPx(): Int = if (SharedData.displayWidth > 0) SharedData.displayWidth else resources.displayMetrics.widthPixels

/**
 * Gets the screen height, preferring the captured display size over the system metrics.
 *
 * @return The height in pixels.
 */
private fun Context.screenHeightPx(): Int = if (SharedData.displayHeight > 0) SharedData.displayHeight else resources.displayMetrics.heightPixels

/**
 * Manages the floating overlay, including:
 * - Rendering the orb and its mini tray for the current run state.
 * - Handling drag placement and "Guidance Overlays".
 * - Handling "Drag to Dismiss" functionality.
 *
 * @property context The application context.
 * @property windowManager The WindowManager to add/remove views.
 */
class FloatingOverlayButton(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    companion object {
        private const val TRAY_AUTO_CLOSE_MS = BotHold.TRAY_HOLD_MS
        private const val TRAY_TICK_MS = 1_000L
        private const val TRAY_GAP_DP = 4f
        private const val TRAY_SHOWN_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        private const val TRAY_HIDDEN_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }

    private val overlayLayoutParamsType =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

    // Tap behavior, read from the app's settings each time the overlay is created.
    private val style: OverlayStyle = OverlayStyle.fromSetting(SharedData.overlayStyle)

    // The orb's diameter plus room for its shadow on every side. The orb window is buttonSizePx square.
    private val orbSizePx: Int = context.dpToPx(SharedData.overlayButtonSizeDP)
    private val shadowPadPx: Int = context.dpToPx(OverlayStateLogic.shadowPadDpFor(SharedData.overlayButtonSizeDP))
    private val buttonSizePx: Int = orbSizePx + shadowPadPx * 2

    private lateinit var orbView: OverlayOrbView
    private val overlayLayoutParams =
        WindowManager.LayoutParams().apply {
            type = overlayLayoutParamsType
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            windowAnimations = android.R.style.Animation_Toast
            gravity = Gravity.TOP or Gravity.START
        }

    // Tray window. It is added the first time it opens, then shown or hidden through its window alpha so its surface is kept.
    private var trayView: OverlayTrayView? = null
    private var isTrayAdded = false
    private var isTrayOpen = false
    private var trayClosedAtMs = 0L

    // Which side of the orb the tray opened on, set by positionTray(). The unfurl pivots on the side facing the orb.
    private var trayOpensRight = true
    private val trayLayoutParams =
        WindowManager.LayoutParams().apply {
            type = overlayLayoutParamsType
            flags = TRAY_HIDDEN_FLAGS
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            alpha = 0f
        }

    // Run state pushed by BotService. A new overlay ignores the last run's outcome until it starts a run of its own.
    private var isRunning = false
    private var isStopping = false
    private var hasStartedRun = false
    private var visual = OverlayVisual.READY

    // Helpers
    private val guidanceOverlays = GuidanceOverlays(context, windowManager, overlayLayoutParamsType)
    private val dragToDismiss = DragToDismiss(context, windowManager, overlayLayoutParamsType)

    // Callbacks
    private var onStartListener: (() -> Unit)? = null
    private var onStopListener: (() -> Unit)? = null
    private var onDismissListener: (() -> Unit)? = null

    // Touch Handling
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Frame callback that is still adding overlay windows, or null once every window has been added.
    private var addWindowsCallback: Choreographer.FrameCallback? = null

    private val closeTrayRunnable = Runnable { closeTray() }
    private val trayTickRunnable: Runnable =
        object : Runnable {
            override fun run() {
                if (!isTrayOpen) return
                refreshTray()
                handler.postDelayed(this, TRAY_TICK_MS)
            }
        }

    // BotStatus and BotHold call this from any thread, so it hops to the main thread.
    private val statusListener: () -> Unit = { handler.post { refresh() } }

    init {
        createOverlayButton()
        BotStatus.addListener(statusListener)
        BotHold.addListener(statusListener)
        refresh()
    }

    /**
     * Creates the orb and adds its window along with the guidance and dismiss windows.
     */
    private fun createOverlayButton() {
        orbView = OverlayOrbView(context, orbSizePx, shadowPadPx)

        setInitialOverlayPosition(forceScreenCenter = true)

        // Reload previous overlay button location.
        val prefs = context.getSharedPreferences("OverlayPrefs", Context.MODE_PRIVATE)
        overlayLayoutParams.x = prefs.getInt("lastX", overlayLayoutParams.x)
        overlayLayoutParams.y = prefs.getInt("lastY", overlayLayoutParams.y)

        // Add the orb last so it stays on top of the guidance and dismiss windows.
        addWindowsOverFrames(guidanceOverlays.windows + dragToDismiss.windows + (orbView to overlayLayoutParams))

        setupTouchListener()

        // Flash the guidance overlays briefly to indicate that the button can be moved.
        guidanceOverlays.flashGuidance()
    }

    /**
     * Adds the overlay windows to the WindowManager one per frame, in order.
     *
     * Each new window allocates and draws its first surface while the main thread waits, which takes tens of milliseconds per window on emulators.
     * Adding every window in the same frame froze the screen for over 300ms when the overlay first appeared.
     *
     * @param windows The views to add, each paired with the layout params to add it with.
     */
    private fun addWindowsOverFrames(windows: List<Pair<View, WindowManager.LayoutParams>>) {
        val pending = ArrayDeque(windows)
        val choreographer = Choreographer.getInstance()
        val callback =
            object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    val (view, params) = pending.removeFirstOrNull() ?: return
                    windowManager.addView(view, params)
                    if (pending.isEmpty()) {
                        addWindowsCallback = null
                    } else {
                        choreographer.postFrameCallback(this)
                    }
                }
            }
        addWindowsCallback = callback
        choreographer.postFrameCallback(callback)
    }

    /**
     * Sets the initial position of the overlay button.
     *
     * @param forceScreenCenter If true, centers on screen regardless of allowed regions.
     */
    private fun setInitialOverlayPosition(forceScreenCenter: Boolean) {
        val screenWidth = context.screenWidthPx()
        val screenHeight = context.screenHeightPx()

        if (!forceScreenCenter && !guidanceOverlays.isFullScreenGuidance) {
            val region = guidanceOverlays.getFirstGuidanceRegion()
            if (region != null) {
                overlayLayoutParams.x = region.x + (region.width - buttonSizePx) / 2
                overlayLayoutParams.y = region.y + (region.height - buttonSizePx) / 2
            } else {
                overlayLayoutParams.x = (screenWidth - buttonSizePx) / 2
                overlayLayoutParams.y = (screenHeight - buttonSizePx) / 2
            }
        } else {
            overlayLayoutParams.x = (screenWidth - buttonSizePx) / 2
            overlayLayoutParams.y = (screenHeight - buttonSizePx) / 2
        }

        if (::orbView.isInitialized && orbView.isAttachedToWindow) {
            windowManager.updateViewLayout(orbView, overlayLayoutParams)
        }
    }

    /**
     * Calculates the center coordinates (X, Y) of the overlay button on screen.
     *
     * @return A Pair of Ints representing the center coordinates (X, Y).
     */
    private fun getOverlayCenter(): Pair<Int, Int> {
        val location = IntArray(2)
        orbView.getLocationOnScreen(location)
        val centerX = location[0] + buttonSizePx / 2
        val centerY = location[1] + buttonSizePx / 2
        return Pair(centerX, centerY)
    }

    /**
     * Sets up the touch listener for dragging the button.
     *
     * @return Boolean indicating if the touch event was handled.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        orbView.setOnTouchListener(
            object : View.OnTouchListener {
                private var initialX: Int = 0
                private var initialY: Int = 0
                private var initialTouchX: Float = 0F
                private var initialTouchY: Float = 0F
                private var isDragging = false
                private var isLongPressTriggered = false

                private val longPressRunnable =
                    Runnable {
                        closeTray(animate = false)
                        orbView.setPressedDip(false)

                        // Highlight dismiss area if it exists.
                        isLongPressTriggered = true
                        dragToDismiss.show()

                        // Show initial guidance around the button.
                        val (centerX, centerY) = getOverlayCenter()
                        if (!guidanceOverlays.isInsideGuidanceRegion(centerX, centerY)) {
                            guidanceOverlays.showGuidance()
                        }
                    }

                override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                    val action = event?.action ?: return false

                    when (action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = overlayLayoutParams.x
                            initialY = overlayLayoutParams.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            isLongPressTriggered = false
                            orbView.setPressedDip(true)

                            // Stop any ongoing flashing animation when the button itself is tapped.
                            guidanceOverlays.stopFlashing()

                            // Schedule the long-press check.
                            handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                            return false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val xDiffRaw = event.rawX - initialTouchX
                            val yDiffRaw = event.rawY - initialTouchY

                            // If we haven't started dragging yet, check if we've moved past the touch slop.
                            if (!isDragging && !isLongPressTriggered) {
                                if (abs(xDiffRaw) > touchSlop || abs(yDiffRaw) > touchSlop) {
                                    // Start showing UI immediately on drag.
                                    isDragging = true
                                    orbView.setPressedDip(false)
                                    closeTray(animate = false)
                                    handler.removeCallbacks(longPressRunnable)
                                    dragToDismiss.show()
                                }
                            }

                            if (isDragging || isLongPressTriggered) {
                                val xDiff = xDiffRaw.roundToInt()
                                val yDiff = yDiffRaw.roundToInt()

                                overlayLayoutParams.x = initialX + xDiff
                                overlayLayoutParams.y = initialY + yDiff
                                windowManager.updateViewLayout(orbView, overlayLayoutParams)

                                val (centerX, centerY) = getOverlayCenter()

                                // Determine if the button is inside the drag-to-dismiss area.
                                val isInsideDismiss = dragToDismiss.isInside(centerX, centerY)
                                dragToDismiss.updateHover(isInsideDismiss)

                                if (isInsideDismiss) {
                                    guidanceOverlays.hideGuidance()
                                } else {
                                    // Show the guidance overlays for the regions based on where the button is located.
                                    if (!guidanceOverlays.isInsideGuidanceRegion(centerX, centerY)) {
                                        guidanceOverlays.showGuidance()
                                    } else {
                                        guidanceOverlays.hideGuidance()
                                    }
                                }
                            }
                            return false
                        }
                        MotionEvent.ACTION_UP -> {
                            handler.removeCallbacks(longPressRunnable)
                            orbView.setPressedDip(false)

                            // If we were dragging or holding, handle the end of that interaction.
                            if (isDragging || isLongPressTriggered) {
                                // Dismiss the button if it is inside the drag-to-dismiss area.
                                if (dragToDismiss.isHovering) {
                                    onDismissListener?.invoke()
                                }

                                dragToDismiss.hide()
                                guidanceOverlays.hideGuidance()

                                // Store the current button location in the preferences
                                // so it can be reloaded the next time the service runs.
                                val editor = context.getSharedPreferences("OverlayPrefs", Context.MODE_PRIVATE).edit()
                                editor.putInt("lastX", overlayLayoutParams.x)
                                editor.putInt("lastY", overlayLayoutParams.y)
                                editor.apply()
                            } else {
                                // This was a tap.
                                handleTap(event.downTime)
                                v?.performClick()
                            }

                            isDragging = false
                            isLongPressTriggered = false
                            return false
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            handler.removeCallbacks(longPressRunnable)
                            orbView.setPressedDip(false)

                            dragToDismiss.hide()
                            guidanceOverlays.hideGuidance()
                            isDragging = false
                            isLongPressTriggered = false
                        }
                    }
                    return false
                }
            },
        )
    }

    /**
     * Registers a callback to be invoked when a tap should start the bot.
     *
     * @param listener Called on the main thread.
     */
    fun setOnStartListener(listener: () -> Unit) {
        onStartListener = listener
    }

    /**
     * Registers a callback to be invoked when a tap should stop the bot.
     *
     * @param listener Called on the main thread.
     */
    fun setOnStopListener(listener: () -> Unit) {
        onStopListener = listener
    }

    /**
     * Registers a callback to be invoked when the overlay button is dismissed.
     *
     * @param listener Called on the main thread.
     */
    fun setOnDismissListener(listener: () -> Unit) {
        onDismissListener = listener
    }

    /**
     * Updates whether a run is in progress.
     *
     * @param running True if the bot is running, false otherwise.
     */
    fun setRunningState(running: Boolean) {
        isRunning = running
        if (running) hasStartedRun = true else isStopping = false
        refresh()
    }

    /**
     * Does what a tap on the orb means in the current state and style.
     *
     * @param downTimeMs When the tap's finger went down, in `SystemClock.uptimeMillis()` time.
     */
    private fun handleTap(downTimeMs: Long) {
        when (OverlayStateLogic.tapActionFor(style, visual)) {
            OverlayTapAction.START -> onStartListener?.invoke()
            OverlayTapAction.STOP -> requestStop()
            OverlayTapAction.RESUME -> BotHold.resume()
            // An outside touch closes the tray at this same tap's down, so a tray closed at or after the down must not reopen.
            OverlayTapAction.OPEN_TRAY -> if (trayClosedAtMs < downTimeMs) openTray()
            OverlayTapAction.NONE -> {}
        }
    }

    /**
     * Shows the stopping state and asks BotService to stop.
     */
    private fun requestStop() {
        if (!isRunning) return
        isStopping = true
        refresh()
        onStopListener?.invoke()
    }

    /**
     * Redraws the orb and the tray from the current run state. Closes the tray when the new state has no buttons.
     */
    private fun refresh() {
        val snapshot = BotStatus.snapshot()
        val outcome = if (hasStartedRun) snapshot.outcome else null
        visual = OverlayStateLogic.visualFor(isRunning, isStopping, BotHold.pauseState, outcome)
        orbView.render(style, visual, snapshot)
        if (isTrayOpen) {
            if (OverlayStateLogic.trayButtonsFor(visual, BotHold.hasSafePoint).isEmpty()) closeTray() else refreshTray()
        }
    }

    /**
     * Opens the tray next to the orb, toward the middle of the screen, and holds the bot while it is open. Tapping the orb while the tray is
     * still closing turns the close around without releasing the bot.
     */
    private fun openTray() {
        if (isTrayOpen || OverlayStateLogic.trayButtonsFor(visual, BotHold.hasSafePoint).isEmpty()) return
        val tray = trayView ?: OverlayTrayView(context, orbSizePx, shadowPadPx, ::onTrayButton, ::restartTrayAutoClose) { closeTray() }.also { trayView = it }
        tray.render(visual, BotStatus.snapshot(), BotHold.hasSafePoint, isHeld = visual == OverlayVisual.RUNNING)
        positionTray(tray)
        setTrayWindowShown(tray, true)
        tray.animateOpen(trayOpensRight)
        isTrayOpen = true
        if (isRunning) BotHold.setTrayOpen(true)
        restartTrayAutoClose()
        handler.removeCallbacks(trayTickRunnable)
        handler.postDelayed(trayTickRunnable, TRAY_TICK_MS)
    }

    /**
     * Closes the tray. It stops taking taps at once, plays its close animation, and only then hides its window and releases the bot, so the bot
     * never reads a half-closed tray.
     *
     * @param animate False to hide the tray and release the bot at once, as for a drag or when the overlay goes away.
     */
    private fun closeTray(animate: Boolean = true) {
        handler.removeCallbacks(closeTrayRunnable)
        handler.removeCallbacks(trayTickRunnable)
        val tray = trayView
        if (!isTrayOpen) {
            // A close that is still animating finishes now when an instant close is asked for.
            if (!animate && tray != null && tray.isAnimating) finishTrayClose()
            return
        }
        isTrayOpen = false
        trayClosedAtMs = SystemClock.uptimeMillis()
        if (tray == null) {
            BotHold.setTrayOpen(false)
            return
        }
        setTrayTouchable(tray, false)
        if (animate) tray.animateClose { finishTrayClose() } else finishTrayClose()
    }

    /**
     * Hides the tray window and releases the bot once the tray is gone.
     */
    private fun finishTrayClose() {
        trayView?.let {
            it.restHidden()
            setTrayWindowShown(it, false)
        }
        BotHold.setTrayOpen(false)
    }

    /**
     * Turns the tray's touches off while it closes, leaving it visible.
     *
     * @param tray The tray view.
     * @param touchable False to let taps pass through to the game.
     */
    private fun setTrayTouchable(tray: OverlayTrayView, touchable: Boolean) {
        trayLayoutParams.flags = if (touchable) TRAY_SHOWN_FLAGS else TRAY_HIDDEN_FLAGS
        if (isTrayAdded) runCatching { windowManager.updateViewLayout(tray, trayLayoutParams) }
    }

    /**
     * Restarts the tray's 3-second auto-close.
     */
    private fun restartTrayAutoClose() {
        handler.removeCallbacks(closeTrayRunnable)
        handler.postDelayed(closeTrayRunnable, TRAY_AUTO_CLOSE_MS)
    }

    /**
     * Re-renders the open tray and keeps it beside the orb, since its width changes with its text and buttons.
     */
    private fun refreshTray() {
        val tray = trayView ?: return
        tray.render(visual, BotStatus.snapshot(), BotHold.hasSafePoint, isHeld = visual == OverlayVisual.RUNNING)
        positionTray(tray)
        tray.updatePivot(trayOpensRight)
        if (isTrayAdded) runCatching { windowManager.updateViewLayout(tray, trayLayoutParams) }
    }

    /**
     * Places the tray beside the orb on the side facing the middle of the screen, vertically centered on the orb. The tray window carries
     * shadow room around its pill, which the placement accounts for.
     *
     * @param tray The tray view.
     */
    private fun positionTray(tray: OverlayTrayView) {
        tray.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val screenWidth = context.screenWidthPx()
        val gap = context.dpToPx(TRAY_GAP_DP)
        trayOpensRight = OverlayStateLogic.trayOpensRight(overlayLayoutParams.x, screenWidth, buttonSizePx)
        trayLayoutParams.x = OverlayStateLogic.trayLeftFor(overlayLayoutParams.x, screenWidth, buttonSizePx, shadowPadPx, gap, tray.measuredWidth, tray.shadowPadPx)
        trayLayoutParams.y = OverlayStateLogic.trayTopFor(overlayLayoutParams.y, context.screenHeightPx(), buttonSizePx, shadowPadPx, tray.measuredHeight)
    }

    /**
     * Shows or hides the tray window. A hidden tray is untouchable so it never eats taps meant for the game.
     *
     * @param tray The tray view.
     * @param shown True to show it, false to hide it.
     */
    private fun setTrayWindowShown(tray: OverlayTrayView, shown: Boolean) {
        trayLayoutParams.alpha = if (shown) 1f else 0f
        trayLayoutParams.flags = if (shown) TRAY_SHOWN_FLAGS else TRAY_HIDDEN_FLAGS
        if (isTrayAdded) {
            runCatching { windowManager.updateViewLayout(tray, trayLayoutParams) }
        } else if (shown) {
            windowManager.addView(tray, trayLayoutParams)
            isTrayAdded = true
        }
    }

    /**
     * Handles a tray button tap.
     *
     * @param button The button that was tapped.
     */
    private fun onTrayButton(button: TrayButton) {
        closeTray()
        when (button) {
            TrayButton.PAUSE -> BotHold.requestPause()
            TrayButton.RESUME -> BotHold.resume()
            TrayButton.STOP -> requestStop()
            TrayButton.START -> onStartListener?.invoke()
        }
    }

    /**
     * Removes all views from the WindowManager and cleans up resources.
     */
    fun cleanup() {
        // Stop adding windows that have not been added yet so none are left behind.
        addWindowsCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        addWindowsCallback = null

        BotStatus.removeListener(statusListener)
        BotHold.removeListener(statusListener)
        closeTray(animate = false)
        handler.removeCallbacksAndMessages(null)

        trayView?.let { tray -> if (isTrayAdded) runCatching { windowManager.removeView(tray) } }
        trayView = null
        isTrayAdded = false

        if (::orbView.isInitialized) {
            try {
                windowManager.removeView(orbView)
            } catch (_: IllegalArgumentException) {
                // View was already removed or not attached.
            }
        }
        guidanceOverlays.cleanup()
        dragToDismiss.cleanup()
    }
}

/**
 * Manages the guidance overlays for the overlay button.
 *
 * This class handles:
 * - Parsing guidance regions from shared data.
 * - Checking if the button is within a valid region.
 * - Displaying a highlight box and tooltip when out of bounds.
 *
 * @param context The application context.
 * @param windowManager The WindowManager instance used to add and remove views.
 * @param overlayLayoutParamsType The type of WindowManager.LayoutParams used for the overlay view.
 */
private class GuidanceOverlays(
    private val context: Context,
    private val windowManager: WindowManager,
    private val overlayLayoutParamsType: Int,
) {
    // One window per guidance region, each sized to that region only, followed by the tooltip window.
    private val guidanceWindows: MutableList<Pair<View, WindowManager.LayoutParams>> = mutableListOf()

    // Handler for scheduling the flash hide callback.
    private val flashHandler = Handler(Looper.getMainLooper())
    private var flashHideRunnable: Runnable? = null
    private var isFlashing: Boolean = false

    private var guidanceRegions: List<GuidanceRegion> = emptyList()

    var isFullScreenGuidance: Boolean = true
        private set

    /** The guidance windows paired with their layout params, for the caller to add to the WindowManager. */
    val windows: List<Pair<View, WindowManager.LayoutParams>>
        get() = guidanceWindows

    /**
     * Represents a rectangular area where the button is suggested to be in.
     *
     * @param x The x-coordinate of the top-left corner of the region.
     * @param y The y-coordinate of the top-left corner of the region.
     * @param width The width of the region.
     * @param height The height of the region.
     */
    data class GuidanceRegion(val x: Int, val y: Int, val width: Int, val height: Int) {
        /**
         * Checks if the given point is inside this region.
         *
         * @param centerX The x-coordinate of the center of the point.
         * @param centerY The y-coordinate of the center of the point.
         * @return True if the point is inside the region, false otherwise.
         */
        fun contains(centerX: Int, centerY: Int): Boolean {
            return centerX in x..(x + width) && centerY in y..(y + height)
        }
    }

    /**
     * Highlights a single guidance region. One instance is added per region to its own
     * region-sized WindowManager window. This keeps the overlay's footprint limited to
     * the region's bounds - areas outside any region have no overlay above the underlying
     * app, so Android's untrusted-touch filtering does not drop taps in those areas.
     */
    @SuppressLint("ViewConstructor")
    private class RegionHighlightView(context: Context, region: GuidanceRegion) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val cornerRadius = density * 8

        private val paintFill =
            Paint().apply {
                color = 0x80444444.toInt()
                style = Paint.Style.FILL
                isAntiAlias = true
            }

        private val paintStroke =
            Paint().apply {
                color = 0x66FFFFFF
                style = Paint.Style.STROKE
                strokeWidth = density * 2
                isAntiAlias = true
            }

        // Local-coordinate rect - the window itself is sized to the region, so we draw at (0, 0).
        private val rect = RectF(0f, 0f, region.width.toFloat(), region.height.toFloat())

        init {
            // Use hardware layer for better rendering performance when visibility changes.
            setLayerType(LAYER_TYPE_HARDWARE, null)
            // Ensure touches pass through to views below.
            isClickable = false
            isFocusable = false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent?): Boolean = false

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paintFill)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paintStroke)
        }
    }

    init {
        // Initialize the guidance regions and create the region guidance overlays.
        initializeGuidanceRegions()
        createRegionGuidanceOverlays()
    }

    /**
     * Parses the guidance regions from SharedData and initializes the guidance regions list.
     * Scales the regions from the baseline configuration (1080x2340) to the current device resolution.
     */
    private fun initializeGuidanceRegions() {
        val screenWidth = context.screenWidthPx()
        val screenHeight = context.screenHeightPx()

        val rawRegions = SharedData.guidanceRegions
        if (rawRegions.isEmpty() || !OverlayConfig.ENABLE_GUIDANCE_OVERLAYS) {
            // If no guidance regions are defined, allow placement anywhere.
            guidanceRegions = emptyList()
            isFullScreenGuidance = true
            return
        }

        // We use the baseline values to scale the coordinates to the current device's resolution.
        val scaleX = screenWidth.toFloat() / SharedData.baselineWidth
        val scaleY = screenHeight.toFloat() / SharedData.baselineHeight

        val processed = mutableListOf<GuidanceRegion>()
        val notchHeight = getNotchHeight(windowManager)

        for (raw in rawRegions) {
            if (raw.size < 4) continue

            // Scale and map the inputs.
            val scaledX = (raw[0] * scaleX).roundToInt()
            // Offset by the notch height to account for display cutouts.
            val scaledY = (raw[1] * scaleY).roundToInt() + notchHeight
            val scaledW = (raw[2] * scaleX).roundToInt()
            val scaledH = (raw[3] * scaleY).roundToInt()

            val x = scaledX.coerceIn(0, screenWidth)

            // For width/height, if 0 or less was provided in raw, it usually meant "rest of screen" or "full".
            // Logic here: if raw[2] <= 0 we take remaining width. If it was positive, we use the scaled width.
            val width = if (raw[2] <= 0) (screenWidth - x).coerceAtLeast(0) else scaledW.coerceAtMost(screenWidth - x)
            val height = if (raw[3] <= 0) (screenHeight - scaledY).coerceAtLeast(0) else scaledH

            // Adjust y-coordinate if the container would extend past the bottom of the screen.
            val y =
                if (scaledY + height > screenHeight) {
                    // Move y up so that the full height fits within the screen.
                    (screenHeight - height).coerceAtLeast(0)
                } else {
                    scaledY.coerceIn(0, screenHeight)
                }

            // Recalculate height based on final y position.
            val finalHeight = height.coerceAtMost(screenHeight - y)

            if (width > 0 && finalHeight > 0) {
                processed.add(GuidanceRegion(x, y, width, finalHeight))
            }
        }

        guidanceRegions = processed
        isFullScreenGuidance = processed.isEmpty()
    }

    /**
     * Creates the visual elements for guidance (highlight box and tooltip). They are added to the WindowManager by the caller through `windows`.
     */
    @SuppressLint("SetTextI18n")
    private fun createRegionGuidanceOverlays() {
        if (!OverlayConfig.ENABLE_GUIDANCE_OVERLAYS) return

        // Fullscreen fallback case: no regions configured.
        if (isFullScreenGuidance || guidanceRegions.isEmpty()) return

        // Create one region-sized window per region. Each is its own WindowManager view so areas outside the regions have no overlay at all.
        for (region in guidanceRegions) {
            val view = RegionHighlightView(context, region)

            val params =
                WindowManager.LayoutParams(
                    region.width,
                    region.height,
                    overlayLayoutParamsType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    x = region.x
                    y = region.y
                    gravity = Gravity.TOP or Gravity.START
                    // Start hidden. See setOverlayWindowShown().
                    alpha = 0f
                }

            guidanceWindows.add(view to params)
        }

        // Create the tooltip view.
        val tooltipView =
            TextView(context).apply {
                text = "Recommended to place the button inside the highlighted area(s)."
                setTextColor(Color.WHITE)
                textSize = 14f
                // Adjusts the padding of the tooltip container.
                setPadding(context.dpToPx(4f), context.dpToPx(4f), context.dpToPx(4f), context.dpToPx(4f))
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(0xCC000000.toInt())
                        cornerRadius = context.dpToPx(8f).toFloat()
                    }
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                // Ensure touches pass through to views below.
                isClickable = false
                isFocusable = false
            }

        val tooltipLayoutParams =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayLayoutParamsType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                windowAnimations = android.R.style.Animation_Toast
                // Center the tooltip on the screen.
                gravity = Gravity.CENTER
                // Start hidden. See setOverlayWindowShown().
                alpha = 0f
            }

        guidanceWindows.add(tooltipView to tooltipLayoutParams)
    }

    /**
     * Checks if the button center is within any guidance region.
     *
     * @param centerX The x-coordinate of the center of the button.
     * @param centerY The y-coordinate of the center of the button.
     * @return True if the button is within any guidance region, false otherwise.
     */
    fun isInsideGuidanceRegion(centerX: Int, centerY: Int): Boolean {
        if (!OverlayConfig.ENABLE_GUIDANCE_OVERLAYS) return true
        if (isFullScreenGuidance || guidanceRegions.isEmpty()) {
            return true
        }
        return guidanceRegions.any { it.contains(centerX, centerY) }
    }

    /**
     * Shows the guidance overlays.
     */
    fun showGuidance() {
        if (!OverlayConfig.ENABLE_GUIDANCE_OVERLAYS) return

        // Return early if the button is allowed to be placed anywhere.
        if (isFullScreenGuidance || guidanceRegions.isEmpty()) {
            return
        }

        // Show every per-region highlight and the tooltip.
        for ((view, params) in guidanceWindows) {
            view.animate().cancel()
            view.alpha = 1f
            setOverlayWindowShown(windowManager, view, params, true)
        }
    }

    /**
     * Hides the guidance overlays.
     */
    fun hideGuidance() {
        for ((view, params) in guidanceWindows) {
            view.animate().cancel()
            setOverlayWindowShown(windowManager, view, params, false)
        }
    }

    /**
     * Flashes the guidance overlays on and off with smooth fade animations to indicate
     * to the user that the overlay button can be moved.
     *
     * @param flashCount The number of times to flash the guidance overlays.
     * @param blinkIntervalMs The interval in milliseconds for each blink cycle (fade in + visible + fade out).
     */
    fun flashGuidance(flashCount: Int = 3, blinkIntervalMs: Long = 1000L) {
        if (!OverlayConfig.ENABLE_GUIDANCE_OVERLAYS) return

        // Return early if the button is allowed to be placed anywhere.
        if (isFullScreenGuidance || guidanceRegions.isEmpty()) {
            return
        }

        // Cancel any existing flash callbacks.
        cancelFlashCallback()

        isFlashing = true

        // The fade duration in milliseconds is the same for both fade in and fade out.
        val fadeDuration = 250L
        var remainingFlashes = flashCount

        // Create a runnable that performs smooth fade animations.
        val blinkRunnable =
            object : Runnable {
                override fun run() {
                    if (remainingFlashes <= 0) {
                        // All flashes completed, fade out and stop.
                        fadeOutGuidance(fadeDuration)
                        isFlashing = false
                        return
                    }

                    remainingFlashes--

                    // Fade in, then schedule fade out.
                    fadeInGuidance(fadeDuration)

                    // Schedule fade out after the visible period.
                    flashHandler.postDelayed({
                        fadeOutGuidance(fadeDuration)
                    }, blinkIntervalMs - fadeDuration)

                    // Schedule the next blink cycle if there are more flashes.
                    if (remainingFlashes > 0) {
                        flashHandler.postDelayed(this, blinkIntervalMs)
                    }
                }
            }

        // Store reference for cleanup and start the blinking.
        flashHideRunnable = blinkRunnable
        flashHandler.post(blinkRunnable)
    }

    /**
     * Fades in the guidance overlays with an animation.
     *
     * @param duration The duration of the fade animation in milliseconds.
     */
    private fun fadeInGuidance(duration: Long) {
        for ((view, params) in guidanceWindows) {
            // Fade from fully transparent if the window was hidden.
            if (params.alpha == 0f) view.alpha = 0f
            setOverlayWindowShown(windowManager, view, params, true)
            view.animate().alpha(1f).setDuration(duration).start()
        }
    }

    /**
     * Fades out the guidance overlays with an animation.
     *
     * @param duration The duration of the fade animation in milliseconds.
     */
    private fun fadeOutGuidance(duration: Long) {
        for ((view, params) in guidanceWindows) {
            view.animate().alpha(0f).setDuration(duration).withEndAction {
                setOverlayWindowShown(windowManager, view, params, false)
            }.start()
        }
    }

    /**
     * Returns the first valid region, useful for initial placement.
     *
     * @return The first valid region, or null if there are no regions.
     */
    fun getFirstGuidanceRegion(): GuidanceRegion? {
        return guidanceRegions.firstOrNull()
    }

    /**
     * Cancels the pending flash callbacks, including fade outs that were already scheduled.
     */
    private fun cancelFlashCallback() {
        flashHandler.removeCallbacksAndMessages(null)
        flashHideRunnable = null
    }

    /**
     * Stops any ongoing flash animation immediately and hides the overlays.
     * Called when the user interacts with the overlay button.
     */
    fun stopFlashing() {
        cancelFlashCallback()

        // Mark flashing as stopped.
        isFlashing = false

        // Cancel any ongoing animations and hide immediately.
        hideGuidance()
    }

    /**
     * Removes overlays from the WindowManager.
     */
    fun cleanup() {
        // Cancel any pending flash callbacks.
        cancelFlashCallback()

        // Remove every per-region highlight view and the tooltip view.
        for ((view, _) in guidanceWindows) {
            view.animate().cancel()
            runCatching { windowManager.removeView(view) }
        }

        guidanceWindows.clear()
    }
}

/**
 * Manages the "Drag to Dismiss" functionality for the overlay button.
 *
 * This class handles:
 * - Creating the visual "X" target at the bottom of the screen.
 * - Detecting if the button is dragged over the target.
 * - Animating the target (scale up) on hover.
 *
 * @param context The application context.
 * @param windowManager The WindowManager instance.
 * @param overlayLayoutParamsType The type of WindowManager.LayoutParams to use.
 */
private class DragToDismiss(
    private val context: Context,
    private val windowManager: WindowManager,
    private val overlayLayoutParamsType: Int,
) {
    private lateinit var dismissTargetView: View
    private lateinit var dismissCircleView: View
    private lateinit var dismissLayoutParams: WindowManager.LayoutParams

    /**
     * True if the button is currently hovering over the dismiss target.
     */
    var isHovering: Boolean = false
        private set

    /** The dismiss target window paired with its layout params, for the caller to add to the WindowManager. Empty when the feature is disabled. */
    val windows: List<Pair<View, WindowManager.LayoutParams>>
        get() = if (::dismissTargetView.isInitialized) listOf(dismissTargetView to dismissLayoutParams) else emptyList()

    init {
        if (OverlayConfig.ENABLE_DISMISS_DRAG) {
            createDismissTargetOverlay()
        }
    }

    /**
     * Creates the dismiss target overlay but keeps it hidden initially. It is added to the WindowManager by the caller through `windows`.
     */
    private fun createDismissTargetOverlay() {
        val targetSizePx = context.dpToPx(SharedData.overlayDismissButtonSizeDP)
        val containerSizePx = (targetSizePx * 1.5f).roundToInt()

        val screenWidth = context.screenWidthPx()
        val screenHeight = context.screenHeightPx()
        val bottomMargin = context.dpToPx(32f) + if (SharedData.displayDPI >= 400) 150 else 50

        dismissTargetView =
            FrameLayout(context).apply {
                // Use hardware layer for better rendering performance when visibility changes.
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }

        // Create the dismiss circle view.
        dismissCircleView =
            FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(targetSizePx, targetSizePx, Gravity.CENTER)
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xCC000000.toInt())
                    }

                val iconView =
                    TextView(context).apply {
                        text = "✕"
                        setTextColor(Color.WHITE)
                        // Limit text size to 40% of the circle size to ensure it fits well.
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, targetSizePx * 0.4f)
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                    }
                addView(
                    iconView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    ),
                )
            }

        (dismissTargetView as FrameLayout).addView(dismissCircleView)

        dismissLayoutParams =
            WindowManager.LayoutParams(
                containerSizePx,
                containerSizePx,
                overlayLayoutParamsType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (screenWidth - containerSizePx) / 2
                y = (screenHeight - containerSizePx - bottomMargin).coerceAtLeast(0)
                windowAnimations = android.R.style.Animation_Toast
                // Start hidden. See setOverlayWindowShown().
                alpha = 0f
            }
    }

    /**
     * Shows the dismiss target with an animation.
     */
    fun show() {
        if (!OverlayConfig.ENABLE_DISMISS_DRAG) return
        if (::dismissTargetView.isInitialized && ::dismissCircleView.isInitialized) {
            setOverlayWindowShown(windowManager, dismissTargetView, dismissLayoutParams, true)
            dismissCircleView.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
        }
    }

    /**
     * Hides the dismiss target.
     */
    fun hide() {
        if (::dismissTargetView.isInitialized && ::dismissCircleView.isInitialized) {
            dismissCircleView.animate().cancel()
            setOverlayWindowShown(windowManager, dismissTargetView, dismissLayoutParams, false)
            dismissCircleView.scaleX = 1f
            dismissCircleView.scaleY = 1f
        }
        isHovering = false
    }

    /**
     * Updates the scale of the target based on hover state. It will magnify if the button is hovered over and go back to normal if not.
     *
     * @param isHoveringParam True if the button is hovered over, false otherwise.
     */
    fun updateHover(isHoveringParam: Boolean) {
        if (!OverlayConfig.ENABLE_DISMISS_DRAG) return
        if (isHovering == isHoveringParam) return

        if (::dismissCircleView.isInitialized) {
            val targetScale = if (isHoveringParam) 1.2f else 1f
            dismissCircleView.animate().scaleX(targetScale).scaleY(targetScale).setDuration(120L).start()
            isHovering = isHoveringParam
        }
    }

    /**
     * Checks if the button center is within the dismiss target's bounds.
     *
     * @param centerX The x-coordinate of the button center.
     * @param centerY The y-coordinate of the button center.
     * @return True if the button center is within the dismiss target's bounds, false otherwise.
     */
    fun isInside(centerX: Int, centerY: Int): Boolean {
        if (!OverlayConfig.ENABLE_DISMISS_DRAG) return false
        if (!::dismissTargetView.isInitialized || !::dismissCircleView.isInitialized || !::dismissLayoutParams.isInitialized || dismissLayoutParams.alpha == 0f) {
            return false
        }

        val location = IntArray(2)
        dismissCircleView.getLocationOnScreen(location)
        val left = location[0]
        val top = location[1]
        val right = left + dismissCircleView.width
        val bottom = top + dismissCircleView.height

        return centerX in left..right && centerY in top..bottom
    }

    /**
     * Removes the dismiss target from the WindowManager.
     */
    fun cleanup() {
        if (::dismissTargetView.isInitialized) {
            runCatching { windowManager.removeView(dismissTargetView) }
        }
    }
}
