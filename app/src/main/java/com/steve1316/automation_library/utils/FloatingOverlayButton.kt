package com.steve1316.automation_library.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import com.steve1316.automation_library.R
import com.steve1316.automation_library.data.SharedData
import android.os.Handler
import android.os.Looper
import android.view.ViewConfiguration
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
 * Manages the floating overlay button, including:
 * - Rendering the button and animations.
 * - Handling drag placement and "Guidance Overlays".
 * - Handling "Drag to Dismiss" functionality.
 *
 * @property context The application context.
 * @property windowManager The WindowManager to add/remove views.
 */
class FloatingOverlayButton(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private val overlayLayoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
    }

    private var buttonSizePx: Int = 0
    private lateinit var overlayView: View
    private lateinit var overlayButton: ImageButton
    private val overlayLayoutParams = WindowManager.LayoutParams().apply {
        type = overlayLayoutParamsType
        flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        format = PixelFormat.TRANSLUCENT
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        windowAnimations = android.R.style.Animation_Toast
        gravity = Gravity.TOP or Gravity.START
    }

    // Animation
    private lateinit var playButtonAnimation: Animation
    private lateinit var playButtonAnimationAlt: Animation
    private lateinit var stopButtonAnimation: Animation
    private var isRunning: Boolean = false

    // Helpers
    private val guidanceOverlays = GuidanceOverlays(context, windowManager, overlayLayoutParamsType)
    private val dragToDismiss = DragToDismiss(context, windowManager, overlayLayoutParamsType)

    // Callbacks
    private var onOverlayClickListener: (() -> Unit)? = null
    private var onDismissListener: (() -> Unit)? = null

    // Touch Handling
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        createOverlayButton()
        initializeAnimations()
        startAnimations()
    }

    /**
     * Inflates and configures the overlay button view.
     */
    @SuppressLint("InflateParams")
    private fun createOverlayButton() {
        overlayView = LayoutInflater.from(context).inflate(R.layout.bot_actions, null)
        overlayButton = overlayView.findViewById(R.id.bot_actions_overlay_button)

        buttonSizePx = context.dpToPx(SharedData.overlayButtonSizeDP)
        overlayButton.layoutParams.width = buttonSizePx
        overlayButton.layoutParams.height = buttonSizePx
        overlayButton.requestLayout()

        setInitialOverlayPosition(forceScreenCenter = true)
        windowManager.addView(overlayView, overlayLayoutParams)

        setupTouchListener()
    }

    /**
     * Sets the initial position of the overlay button.
     * 
     * @param forceScreenCenter If true, centers on screen regardless of allowed regions.
     */
    private fun setInitialOverlayPosition(forceScreenCenter: Boolean) {
        val screenWidth = if (SharedData.displayWidth > 0) SharedData.displayWidth else context.resources.displayMetrics.widthPixels
        val screenHeight = if (SharedData.displayHeight > 0) SharedData.displayHeight else context.resources.displayMetrics.heightPixels

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

        if (::overlayView.isInitialized && overlayView.isAttachedToWindow) {
            windowManager.updateViewLayout(overlayView, overlayLayoutParams)
        }
    }

    /**
     * Calculates the center coordinates (X, Y) of the overlay button on screen.
     * 
     * @return A Pair of Ints representing the center coordinates (X, Y).
     */
    private fun getOverlayCenter(): Pair<Int, Int> {
        val location = IntArray(2)
        overlayView.getLocationOnScreen(location)
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
        overlayButton.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0F
            private var initialTouchY: Float = 0F
            private var isDragging = false
            private var isLongPressTriggered = false

            private val longPressRunnable = Runnable {
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
                                handler.removeCallbacks(longPressRunnable)
                                dragToDismiss.show() 
                            }
                        }

                        if (isDragging || isLongPressTriggered) {
                            val xDiff = xDiffRaw.roundToInt()
                            val yDiff = yDiffRaw.roundToInt()
                            
                            overlayLayoutParams.x = initialX + xDiff
                            overlayLayoutParams.y = initialY + yDiff
                            windowManager.updateViewLayout(overlayView, overlayLayoutParams)

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
                        
                        // If we were dragging or holding, handle the end of that interaction.
                        if (isDragging || isLongPressTriggered) {
                            // Dismiss the button if it is inside the drag-to-dismiss area.
                            if (dragToDismiss.isHovering) {
                                onDismissListener?.invoke()
                            }

                            dragToDismiss.hide()
                            guidanceOverlays.hideGuidance()
                        } else {
                            // This was a tap.
                            onOverlayClickListener?.invoke()
                            v?.performClick()
                        }
                        
                        isDragging = false
                        isLongPressTriggered = false
                        return false
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(longPressRunnable)
                        dragToDismiss.hide()
                        guidanceOverlays.hideGuidance()
                        isDragging = false
                        isLongPressTriggered = false
                    }
                }
                return false
            }
        })
    }

    /**
     * Loads the animation resources for the floating overlay button.
     */
    private fun initializeAnimations() {
        playButtonAnimation = AnimationUtils.loadAnimation(context, R.anim.play_button_animation)
        playButtonAnimationAlt = AnimationUtils.loadAnimation(context, R.anim.play_button_animation_alt)
        stopButtonAnimation = AnimationUtils.loadAnimation(context, R.anim.stop_button_animation)

        // These listeners are used to alternate between the play and play-alt animations.
        playButtonAnimation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}
            override fun onAnimationEnd(animation: Animation?) {
                if (!isRunning) {
                    overlayButton.startAnimation(playButtonAnimation)
                }
            }
            override fun onAnimationRepeat(animation: Animation?) {}
        })
        playButtonAnimationAlt.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}
            override fun onAnimationEnd(animation: Animation?) {
                if (!isRunning) {
                    overlayButton.startAnimation(playButtonAnimationAlt)
                }
            }
            override fun onAnimationRepeat(animation: Animation?) {}
        })

        // This listener is used to alternate between the stop and play animations.
        stopButtonAnimation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}
            override fun onAnimationEnd(animation: Animation?) {
                if (isRunning) {
                    overlayButton.startAnimation(stopButtonAnimation)
                }
            }
            override fun onAnimationRepeat(animation: Animation?) {}
        })
    }

    /**
     * Starts the appropriate button animation based on the current state.
     */
    private fun startAnimations() {
        overlayButton.clearAnimation()
        if (isRunning) {
            overlayButton.startAnimation(stopButtonAnimation)
        } else {
            overlayButton.startAnimation(playButtonAnimationAlt)
        }
    }

    /**
     * Registers a callback to be invoked when the overlay button is clicked.
     */
    fun setOnClickListener(listener: () -> Unit) {
        onOverlayClickListener = listener
    }

    /**
     * Registers a callback to be invoked when the overlay button is dismissed.
     */
    fun setOnDismissListener(listener: () -> Unit) {
        onDismissListener = listener
    }

    /**
     * Updates the visual state of the button (icon and animation) based on whether the bot is running.
     * 
     * @param running True if the bot is running, false otherwise.
     */
    fun setRunningState(running: Boolean) {
        // Set the running state flag for FloatingOverlayButton.
        isRunning = running
        if (isRunning) {
            overlayButton.setImageResource(R.drawable.stop_circle_filled)
        } else {
            overlayButton.setImageResource(R.drawable.play_circle_filled)
        }
        startAnimations()
    }

    /**
     * Removes all views from the WindowManager and cleans up resources.
     */
    fun cleanup() {
        if (::overlayButton.isInitialized) {
            overlayButton.clearAnimation()
        }
        if (::overlayView.isInitialized) {
            try {
                windowManager.removeView(overlayView)
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
    private val overlayLayoutParamsType: Int
) {
    private lateinit var regionHighlightsView: RegionHighlightsView
    private lateinit var regionHighlightLayoutParams: WindowManager.LayoutParams
    private lateinit var tooltipView: TextView
    private lateinit var tooltipLayoutParams: WindowManager.LayoutParams

    private var guidanceRegions: List<GuidanceRegion> = emptyList()

    var isFullScreenGuidance: Boolean = true
        private set

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
     * Custom View to draw all guidance regions simultaneously.
     */
    @SuppressLint("ViewConstructor")
    private class RegionHighlightsView(context: Context, private val regions: List<GuidanceRegion>) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val cornerRadius = density * 8

        private val paintFill = android.graphics.Paint().apply {
            color = 0x80444444.toInt()
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }

        private val paintStroke = android.graphics.Paint().apply {
            color = 0x66FFFFFF
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = density * 2
            isAntiAlias = true
        }

        @SuppressLint("DrawAllocation")
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            // Draw all guidance regions.
            for (region in regions) {
                val rect = android.graphics.RectF(
                    region.x.toFloat(),
                    region.y.toFloat(),
                    (region.x + region.width).toFloat(),
                    (region.y + region.height).toFloat()
                )
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paintFill)
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paintStroke)
            }
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
        val screenWidth = if (SharedData.displayWidth > 0) SharedData.displayWidth else context.resources.displayMetrics.widthPixels
        val screenHeight = if (SharedData.displayHeight > 0) SharedData.displayHeight else context.resources.displayMetrics.heightPixels

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
        for (raw in rawRegions) {
            if (raw.size < 4) continue

            // Scale and map the inputs
            val scaledX = (raw[0] * scaleX).roundToInt()
            val scaledY = (raw[1] * scaleY).roundToInt()
            val scaledW = (raw[2] * scaleX).roundToInt()
            val scaledH = (raw[3] * scaleY).roundToInt()

            val x = scaledX.coerceIn(0, screenWidth)
            val y = scaledY.coerceIn(0, screenHeight)
            val maxWidth = (screenWidth - x).coerceAtLeast(0)
            val maxHeight = (screenHeight - y).coerceAtLeast(0)
            
            // For width/height, if 0 or less was provided in raw, it usually meant "rest of screen" or "full".
            // Logic here: if raw[2] <= 0 we take maxWidth. If it was positive, we use the scaled width clamped to maxWidth.
            val width = if (raw[2] <= 0) maxWidth else scaledW.coerceAtMost(maxWidth)
            val height = if (raw[3] <= 0) maxHeight else scaledH.coerceAtMost(maxHeight)
            
            if (width > 0 && height > 0) {
                processed.add(GuidanceRegion(x, y, width, height))
            }
        }

        guidanceRegions = processed
        isFullScreenGuidance = processed.isEmpty()
    }

    /**
     * Creates the visual elements for guidance (highlight box and tooltip).
     */
    @SuppressLint("SetTextI18n")
    private fun createRegionGuidanceOverlays() {
        if (!OverlayConfig.ENABLE_GUIDANCE_OVERLAYS) return

        // Create the full-screen region highlights view.
        regionHighlightsView = RegionHighlightsView(context, guidanceRegions).apply {
            visibility = View.GONE
        }

        val screenWidth = if (SharedData.displayWidth > 0) SharedData.displayWidth else context.resources.displayMetrics.widthPixels
        val screenHeight = if (SharedData.displayHeight > 0) SharedData.displayHeight else context.resources.displayMetrics.heightPixels

        regionHighlightLayoutParams = WindowManager.LayoutParams(
            screenWidth,
            screenHeight,
            overlayLayoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            x = 0
            y = 0
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(regionHighlightsView, regionHighlightLayoutParams)

        // Create the tooltip view.
        tooltipView = TextView(context).apply {
            visibility = View.GONE
            text = "Recommended to place the button inside the highlighted area(s)."
            setTextColor(Color.WHITE)
            textSize = 14f
            // Adjusts the padding of the tooltip container.
            setPadding(context.dpToPx(4f), context.dpToPx(4f), context.dpToPx(4f), context.dpToPx(4f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xCC000000.toInt())
                cornerRadius = context.dpToPx(8f).toFloat()
            }
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }

        tooltipLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayLayoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            windowAnimations = android.R.style.Animation_Toast
            // Center the tooltip on the screen.
            gravity = Gravity.CENTER
        }

        windowManager.addView(tooltipView, tooltipLayoutParams)
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

        // Show the highlights view.
        if (::regionHighlightsView.isInitialized) {
            regionHighlightsView.visibility = View.VISIBLE
        }

        // Show the tooltip view.
        if (::tooltipView.isInitialized) {
            tooltipView.visibility = View.VISIBLE
        }
    }

    /**
     * Hides the guidance overlays.
     */
    fun hideGuidance() {
        // Hide the region highlight and tooltip views.
        if (::regionHighlightsView.isInitialized) {
            regionHighlightsView.visibility = View.GONE
        }
        if (::tooltipView.isInitialized) {
            tooltipView.visibility = View.GONE
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
     * Removes overlays from the WindowManager.
     */
    fun cleanup() {
        // Remove the region highlight and tooltip views.
        if (::regionHighlightsView.isInitialized) {
            runCatching { windowManager.removeView(regionHighlightsView) }
        }
        if (::tooltipView.isInitialized) {
            runCatching { windowManager.removeView(tooltipView) }
        }
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
    private val overlayLayoutParamsType: Int
) {
    private lateinit var dismissTargetView: View
    private lateinit var dismissCircleView: View
    private lateinit var dismissLayoutParams: WindowManager.LayoutParams
    
    /**
     * True if the button is currently hovering over the dismiss target.
     */
    var isHovering: Boolean = false
        private set

    init {
        if (OverlayConfig.ENABLE_DISMISS_DRAG) {
            createDismissTargetOverlay()
        }
    }

    /**
     * Creates the dismiss target overlay but keeps it hidden initially.
     */
    private fun createDismissTargetOverlay() {
        val targetSizePx = context.dpToPx(SharedData.overlayDismissButtonSizeDP)
        val containerSizePx = (targetSizePx * 1.5f).roundToInt()

        val screenWidth = if (SharedData.displayWidth > 0) SharedData.displayWidth else context.resources.displayMetrics.widthPixels
        val screenHeight = if (SharedData.displayHeight > 0) SharedData.displayHeight else context.resources.displayMetrics.heightPixels
        val bottomMargin = context.dpToPx(32f) + if (SharedData.displayDPI >= 400) 150 else 50

        dismissTargetView = FrameLayout(context).apply {
            visibility = View.GONE
        }

        // Create the dismiss circle view.
        dismissCircleView = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(targetSizePx, targetSizePx, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC000000.toInt())
            }

            val iconView = TextView(context).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                // Limit text size to 40% of the circle size to ensure it fits well.
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, targetSizePx * 0.4f)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            addView(
                iconView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }

        (dismissTargetView as FrameLayout).addView(dismissCircleView)

        dismissLayoutParams = WindowManager.LayoutParams(
            containerSizePx,
            containerSizePx,
            overlayLayoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - containerSizePx) / 2
            y = (screenHeight - containerSizePx - bottomMargin).coerceAtLeast(0)
            windowAnimations = android.R.style.Animation_Toast
        }

        windowManager.addView(dismissTargetView, dismissLayoutParams)
    }

    /**
     * Shows the dismiss target with an animation.
     */
    fun show() {
        if (!OverlayConfig.ENABLE_DISMISS_DRAG) return
        if (::dismissTargetView.isInitialized && ::dismissCircleView.isInitialized) {
            dismissTargetView.visibility = View.VISIBLE
            dismissCircleView.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
        }
    }

    /**
     * Hides the dismiss target.
     */
    fun hide() {
        if (::dismissTargetView.isInitialized && ::dismissCircleView.isInitialized) {
            dismissCircleView.animate().cancel()
            dismissTargetView.visibility = View.GONE
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
        if (!::dismissTargetView.isInitialized || !::dismissCircleView.isInitialized || !::dismissLayoutParams.isInitialized || dismissTargetView.visibility != View.VISIBLE) {
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
