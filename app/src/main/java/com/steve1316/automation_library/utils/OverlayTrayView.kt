package com.steve1316.automation_library.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The overlay's mini tray: the run status on the left and its buttons on the right, on a glass pill. It sizes itself from the orb so both
 * scale together, and leaves `shadowPadPx` around the pill for its shadow.
 *
 * @param context The service context.
 * @param orbSizePx Diameter of the orb in pixels. The pill is exactly this tall.
 * @param shadowPadPx Room around the pill for its shadow, in pixels, on every side.
 * @param onButton Called when one of the tray's buttons is tapped.
 * @param onInteract Called when the user touches the tray, so its auto-close can restart.
 * @param onOutsideTouch Called when the user touches anywhere outside the tray.
 */
@SuppressLint("ViewConstructor")
internal class OverlayTrayView(
    context: Context,
    private val orbSizePx: Int,
    val shadowPadPx: Int,
    private val onButton: (TrayButton) -> Unit,
    private val onInteract: () -> Unit,
    private val onOutsideTouch: () -> Unit,
) : LinearLayout(context) {
    // Pixels per design unit, matching the orb.
    private val unit = orbSizePx / OverlayOrbView.BASE_SIZE_DP
    private val headline =
        TextView(context).apply {
            setTextColor(OverlayColors.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f * unit)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            maxLines = 1
        }
    private val detail =
        TextView(context).apply {
            setTextColor(OverlayColors.TRAY_SUBTEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 8.5f * unit)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (150f * unit).roundToInt()
        }
    private val progressBar = TrayProgressBar(context)
    private val buttonRow =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    /** The readout and the buttons, which fade separately from the pill when the tray opens and closes. */
    val content =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
    private var shownButtons: List<TrayButton> = emptyList()

    // The open or close animation in progress, or null when the tray is at rest.
    private var animator: AnimatorSet? = null

    /** True while the tray is opening or closing. */
    val isAnimating: Boolean get() = animator != null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Shadow layers on shapes need a software layer before Android 9. The tray is small, so this costs little.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        val pad = (4f * unit).roundToInt()
        setPadding(shadowPadPx + pad, shadowPadPx, shadowPadPx + pad, shadowPadPx)
        background = GlassPillDrawable(unit, shadowPadPx)
        val readout =
            LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(pad, 0, (6f * unit).roundToInt(), 0)
                addView(headline)
                addView(detail)
                addView(progressBar, LayoutParams(LayoutParams.MATCH_PARENT, (2f * unit).roundToInt().coerceAtLeast(2)).apply { topMargin = (3f * unit).roundToInt() })
            }
        content.addView(readout)
        content.addView(buttonRow)
        addView(content)
        applyPose(OverlayMotion.HIDDEN_POSE)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The pill is always exactly as tall as the orb, plus the shadow room above and below.
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(orbSizePx + shadowPadPx * 2, MeasureSpec.EXACTLY))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutsideTouch()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onInteract()
        return super.dispatchTouchEvent(event)
    }

    /**
     * Updates the tray's text, progress bar, and buttons.
     *
     * @param visual What the overlay is showing.
     * @param snapshot The current run status.
     * @param canPause True when the run has reached a safe point, so the Pause button is offered.
     * @param isHeld True while the tray holds a running bot, which shows the HELD tag.
     */
    fun render(visual: OverlayVisual, snapshot: BotStatus.Snapshot, canPause: Boolean, isHeld: Boolean) {
        val line = OverlayStateLogic.headlineFor(visual, snapshot, isHeld)
        headline.text =
            SpannableStringBuilder(line.text).apply {
                if (line.tag != null) {
                    val start = length
                    append(line.tag)
                    setSpan(PillTagSpan(line.tagColor, unit), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        detail.text = OverlayStateLogic.trayDetailFor(visual, snapshot, BotHold.pauseReason, isHeld)
        progressBar.visibility = if (snapshot.total > 0) View.VISIBLE else View.GONE
        progressBar.set(snapshot.fraction, OverlayStateLogic.ringFor(visual, snapshot).color)

        val buttons = OverlayStateLogic.trayButtonsFor(visual, canPause)
        if (buttons != shownButtons) {
            shownButtons = buttons
            buttonRow.removeAllViews()
            val size = (32f * unit).roundToInt()
            val gap = (5f * unit).roundToInt()
            buttons.forEachIndexed { index, button ->
                val view = OverlayTrayButtonView(context, button, size, unit).apply { setOnClickListener { onButton(button) } }
                buttonRow.addView(view, LayoutParams(size, size).apply { if (index > 0) marginStart = gap })
            }
        }
    }

    /**
     * Keeps the unfurl anchored on the pill's edge that faces the orb, since the tray's width changes with its text.
     *
     * @param opensRight True when the tray sits to the right of the orb.
     */
    fun updatePivot(opensRight: Boolean) {
        pivotX = OverlayStateLogic.unfurlPivotX(opensRight, shadowPadPx, measuredWidth)
        pivotY = measuredHeight / 2f
    }

    /**
     * Unfurls the tray out of the orb: it grows sideways with a slight overshoot while it fades in, then its text and buttons fade in. When a
     * close is still running, it turns around from where it is instead of starting over.
     *
     * @param opensRight True when the tray sits to the right of the orb.
     */
    fun animateOpen(opensRight: Boolean) {
        val fromClose = animator != null
        cancelAnimation()
        updatePivot(opensRight)
        if (!fromClose) applyPose(OverlayMotion.HIDDEN_POSE)
        val scale =
            ObjectAnimator.ofFloat(this, View.SCALE_X, scaleX, 1f).apply {
                duration = OverlayMotion.TRAY_OPEN_MS
                interpolator = PathInterpolator(0.34f, 1.45f, 0.5f, 1f)
            }
        val fade =
            ObjectAnimator.ofFloat(this, View.ALPHA, alpha, 1f).apply {
                duration = OverlayMotion.TRAY_FADE_IN_MS
                interpolator = DecelerateInterpolator()
            }
        val contentFade =
            ObjectAnimator.ofFloat(content, View.ALPHA, content.alpha, 1f).apply {
                startDelay = if (fromClose) 0L else OverlayMotion.CONTENT_FADE_IN_DELAY_MS
                duration = OverlayMotion.CONTENT_FADE_IN_MS
            }
        start(AnimatorSet().apply { playTogether(scale, fade, contentFade) }) {}
    }

    /**
     * Closes the tray into the orb: its text and buttons fade first, then it shrinks back and fades out, with no overshoot.
     *
     * @param onEnd Called once the tray is fully gone. Not called if the close is cancelled by a reopen or an instant close.
     */
    fun animateClose(onEnd: () -> Unit) {
        cancelAnimation()
        val contentFade = ObjectAnimator.ofFloat(content, View.ALPHA, content.alpha, 0f).apply { duration = OverlayMotion.CONTENT_FADE_OUT_MS }
        val scale =
            ObjectAnimator.ofFloat(this, View.SCALE_X, scaleX, OverlayMotion.UNFURL_START_SCALE).apply {
                duration = OverlayMotion.TRAY_CLOSE_MS
                interpolator = AccelerateInterpolator()
            }
        val fade =
            ObjectAnimator.ofFloat(this, View.ALPHA, alpha, 0f).apply {
                duration = OverlayMotion.TRAY_CLOSE_MS
                interpolator = AccelerateInterpolator()
            }
        start(AnimatorSet().apply { playTogether(contentFade, scale, fade) }, onEnd)
    }

    /**
     * Stops any animation and leaves the tray invisible at its unfurl start, for an instant hide or the end of a close. The hidden window keeps
     * this last frame, so the next open never flashes the old tray.
     */
    fun restHidden() {
        cancelAnimation()
        applyPose(OverlayMotion.HIDDEN_POSE)
    }

    override fun onDetachedFromWindow() {
        cancelAnimation()
        super.onDetachedFromWindow()
    }

    /**
     * Starts an animation set and calls `onEnd` when it finishes on its own.
     *
     * @param set The animation set.
     * @param onEnd Called when the set ends without being cancelled.
     */
    private fun start(set: AnimatorSet, onEnd: () -> Unit) {
        set.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator !== set) return
                    animator = null
                    onEnd()
                }
            },
        )
        animator = set
        set.start()
    }

    /**
     * Sets the tray's scale and alphas at once.
     *
     * @param pose The pose to show.
     */
    private fun applyPose(pose: TrayPose) {
        scaleX = pose.scaleX
        alpha = pose.alpha
        content.alpha = pose.contentAlpha
    }

    /**
     * Cancels the running animation without calling its end callback.
     */
    private fun cancelAnimation() {
        val running = animator ?: return
        animator = null
        running.removeAllListeners()
        running.cancel()
    }
}

/**
 * The tray's thin progress bar under its second line.
 *
 * @param context The service context.
 */
@SuppressLint("ViewConstructor")
private class TrayProgressBar(context: Context) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OverlayColors.PROGRESS_TRACK }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var fraction = 0f

    /**
     * Sets how full the bar is and its color.
     *
     * @param fraction How full, from 0 to 1.
     * @param color The fill color.
     */
    fun set(fraction: Float, color: Int) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped == this.fraction && color == fillPaint.color) return
        this.fraction = clamped
        fillPaint.color = color
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Asks for no width of its own, so the bar spans the readout's text instead of stretching the tray to the screen's width.
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else 0
        setMeasuredDimension(width, getDefaultSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, trackPaint)
        if (fraction > 0f) {
            rect.set(0f, 0f, width * fraction, height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        }
    }
}

/**
 * One round tray button: a top-to-bottom gradient with a top highlight and its glyph. It dims while pressed.
 *
 * @param context The service context.
 * @param button Which button this is.
 * @param sizePx Diameter of the button in pixels.
 * @param unit Pixels per design unit, matching the orb.
 */
@SuppressLint("ViewConstructor")
private class OverlayTrayButtonView(context: Context, private val button: TrayButton, private val sizePx: Int, private val unit: Float) : View(context) {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = unit
            strokeCap = Paint.Cap.ROUND
            color = OverlayColors.BUTTON_HIGHLIGHT
        }
    private val highlightRect = RectF()

    init {
        contentDescription = button.label
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backgroundPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), button.topColor, button.bottomColor, Shader.TileMode.CLAMP)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        alpha = if (pressed) OverlayMotion.PRESSED_BUTTON_ALPHA else 1f
    }

    override fun onDraw(canvas: Canvas) {
        val center = sizePx / 2f
        canvas.drawCircle(center, center, center, backgroundPaint)
        val inset = 1.2f * unit
        highlightRect.set(inset, inset, sizePx - inset, sizePx - inset)
        canvas.drawArc(highlightRect, 215f, 110f, false, highlightPaint)
        OverlayGlyphs.draw(canvas, button.glyph, center, center, sizePx * 15f / 32f, OverlayColors.WHITE)
    }
}
