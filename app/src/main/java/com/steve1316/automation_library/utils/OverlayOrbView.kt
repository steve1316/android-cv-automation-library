package com.steve1316.automation_library.utils

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * The round overlay orb. Draws the glass circle, the progress ring, the center glyph or step number, and the breathing dot for the current state.
 * The ring eases to new progress values and the state colors cross-fade instead of jumping.
 *
 * @param context The service context.
 * @param orbSizePx Diameter of the orb in pixels.
 * @param shadowPadPx Space around the orb for its shadow, in pixels. The view is `orbSizePx + 2 * shadowPadPx` on each side.
 */
@SuppressLint("ViewConstructor")
internal class OverlayOrbView(context: Context, private val orbSizePx: Int, private val shadowPadPx: Int) : View(context) {
    companion object {
        /** Orb size the mockups were drawn at, in dp. Every other measurement scales from it. */
        const val BASE_SIZE_DP = 40f

        /** Space around the orb and the tray for their shadows, in design units: the soft shadow's blur plus its drop, with a little spare. */
        const val SHADOW_PAD_UNITS = 8f
    }

    private val totalSizePx = orbSizePx + shadowPadPx * 2

    // Pixels per design unit, so a 50dp orb draws everything 25% larger than the 40dp mockup.
    private val unit = orbSizePx / BASE_SIZE_DP
    private val arcRect = RectF()
    private val highlightRect = RectF()

    // The soft wide shadow comes from its own circle under the fill, so the orb gets two shadow layers.
    private val softShadowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = OverlayColors.GLASS_BOTTOM
            setShadowLayer(5.5f * unit, 0f, 2f * unit, OverlayColors.SHADOW_SOFT)
        }

    // The top-lit gradient shader is set in onSizeChanged().
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(unit, 0f, 0.5f * unit, OverlayColors.SHADOW_TIGHT) }
    private val highlightPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = unit
            strokeCap = Paint.Cap.ROUND
            color = OverlayColors.GLASS_HIGHLIGHT
        }
    private val edgePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = unit
            color = OverlayColors.ORB_STROKE
        }
    private val trackPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.6f * unit
            color = OverlayColors.RING_TRACK
        }
    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.6f * unit
            strokeCap = Paint.Cap.ROUND
        }
    private val turnPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = OverlayColors.WHITE
            textSize = 13f * unit
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            // Fixed-width digits so the number does not jiggle as it changes.
            fontFeatureSettings = "tnum"
        }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OverlayColors.GREEN }
    private val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OverlayColors.ORB_FILL }
    private val colorEvaluator = ArgbEvaluator()

    private var style = OverlayStyle.TRAY
    private var visual = OverlayVisual.READY
    private var snapshot = BotStatus.Snapshot()
    private var ring = OverlayStateLogic.ringFor(visual, snapshot)
    private var targetGlyphColor = OverlayStateLogic.glyphColorFor(visual)
    private var turnText = ""
    private var hasRendered = false
    private var spinDegrees = 0f
    private var dotAlpha = 1f

    // What is drawn right now. These ease toward `ring` and `targetGlyphColor`.
    private var drawnFraction = 0f
    private var drawnRingColor = ring.color
    private var drawnGlyphColor = targetGlyphColor

    private var fractionAnimator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null
    private val spinAnimator =
        ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                spinDegrees = it.animatedValue as Float
                invalidate()
            }
        }
    private val breatheAnimator =
        ValueAnimator.ofFloat(1f, 0.4f).apply {
            duration = 1_000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                dotAlpha = it.animatedValue as Float
                invalidate()
            }
        }

    init {
        // Shadow layers on shapes need a software layer before Android 9. The view is tiny, so this costs little.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        // Clickable so the view keeps receiving MOVE and UP after the touch listener returns false on DOWN.
        isClickable = true
    }

    /**
     * Updates what the orb shows. The first render snaps to the state. Later renders ease the ring and cross-fade the colors.
     *
     * @param style The overlay style.
     * @param visual What the overlay is showing.
     * @param snapshot The current run status.
     */
    fun render(style: OverlayStyle, visual: OverlayVisual, snapshot: BotStatus.Snapshot) {
        if (hasRendered && this.style == style && this.visual == visual && this.snapshot == snapshot) return
        val newRing = OverlayStateLogic.ringFor(visual, snapshot)
        val newGlyphColor = OverlayStateLogic.glyphColorFor(visual)
        if (!hasRendered) {
            drawnFraction = newRing.fraction
            drawnRingColor = newRing.color
            drawnGlyphColor = newGlyphColor
        } else {
            if (newRing.fraction != ring.fraction) animateFraction(newRing.fraction)
            if (newRing.color != ring.color || newGlyphColor != targetGlyphColor) animateColors(newRing.color, newGlyphColor)
        }
        hasRendered = true
        this.style = style
        this.visual = visual
        this.snapshot = snapshot
        ring = newRing
        targetGlyphColor = newGlyphColor
        turnText = snapshot.current.toString()
        val description = OverlayStateLogic.describe(style, visual)
        if (contentDescription != description) contentDescription = description
        updateAnimators()
        invalidate()
    }

    /**
     * Dips the orb while it is pressed and brings it back on release. The window keeps its size, since the dip draws inside it.
     *
     * @param pressed True while a finger is down on the orb.
     */
    fun setPressedDip(pressed: Boolean) {
        val scale = if (pressed) OverlayMotion.PRESS_SCALE else 1f
        animate().scaleX(scale).scaleY(scale).setDuration(OverlayMotion.PRESS_MS).start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(totalSizePx, totalSizePx)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val center = totalSizePx / 2f
        val radius = orbSizePx / 2f
        fillPaint.shader = LinearGradient(0f, center - radius, 0f, center + radius, OverlayColors.GLASS_TOP, OverlayColors.GLASS_BOTTOM, Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimators()
    }

    override fun onDetachedFromWindow() {
        spinAnimator.cancel()
        breatheAnimator.cancel()
        fractionAnimator?.cancel()
        colorAnimator?.cancel()
        animate().cancel()
        // Snap to the state, since render() skips an unchanged state after the view comes back.
        drawnFraction = ring.fraction
        drawnRingColor = ring.color
        drawnGlyphColor = targetGlyphColor
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val center = totalSizePx / 2f
        val radius = orbSizePx / 2f
        canvas.drawCircle(center, center, radius, softShadowPaint)
        canvas.drawCircle(center, center, radius, fillPaint)
        canvas.drawCircle(center, center, radius - unit / 2f, edgePaint)

        // A short bright arc along the top inside edge, like light on glass.
        val highlightInset = 1.6f * unit
        highlightRect.set(center - radius + highlightInset, center - radius + highlightInset, center + radius - highlightInset, center + radius - highlightInset)
        canvas.drawArc(highlightRect, 215f, 110f, false, highlightPaint)

        // The ring sits on the orb's edge. The filled arc glows faintly in its own color.
        val inset = 1.5f * unit
        arcRect.set(center - radius + inset, center - radius + inset, center + radius - inset, center + radius - inset)
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)
        if (drawnFraction > 0f) {
            ringPaint.color = drawnRingColor
            ringPaint.setShadowLayer(unit, 0f, 0f, drawnRingColor)
            canvas.drawArc(arcRect, -90f, 360f * drawnFraction, false, ringPaint)
        }
        if (ring.spinning) {
            ringPaint.clearShadowLayer()
            ringPaint.color = OverlayColors.SPINNER
            canvas.drawArc(arcRect, spinDegrees - 90f, 360f * 0.22f, false, ringPaint)
        }

        val glyph = OverlayStateLogic.orbCenterFor(style, visual, snapshot)
        if (glyph == null) {
            val baseline = center - (turnPaint.descent() + turnPaint.ascent()) / 2f
            canvas.drawText(turnText, center, baseline, turnPaint)
        } else {
            OverlayGlyphs.draw(canvas, glyph, center, center, 20f * unit, drawnGlyphColor)
        }

        if (OverlayStateLogic.showsBreathingDot(visual)) {
            val dotX = center + 14.5f * unit
            val dotY = center - 14.5f * unit
            canvas.drawCircle(dotX, dotY, 5f * unit, dotBorderPaint)
            dotPaint.alpha = (dotAlpha * 255f).toInt()
            canvas.drawCircle(dotX, dotY, 3.5f * unit, dotPaint)
        }
    }

    /**
     * Eases the drawn ring fill toward a new progress value.
     *
     * @param target The new fill, from 0 to 1.
     */
    private fun animateFraction(target: Float) {
        fractionAnimator?.cancel()
        fractionAnimator =
            ValueAnimator.ofFloat(drawnFraction, target).apply {
                duration = OverlayMotion.RING_EASE_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    drawnFraction = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
    }

    /**
     * Cross-fades the drawn ring and glyph colors toward new ones.
     *
     * @param ringTarget The new ring color.
     * @param glyphTarget The new glyph color.
     */
    private fun animateColors(ringTarget: Int, glyphTarget: Int) {
        colorAnimator?.cancel()
        val ringFrom = drawnRingColor
        val glyphFrom = drawnGlyphColor
        colorAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = OverlayMotion.COLOR_FADE_MS
                addUpdateListener {
                    val t = it.animatedFraction
                    drawnRingColor = colorEvaluator.evaluate(t, ringFrom, ringTarget) as Int
                    drawnGlyphColor = colorEvaluator.evaluate(t, glyphFrom, glyphTarget) as Int
                    invalidate()
                }
                start()
            }
    }

    /**
     * Runs only the looping animations the current state needs, and only while the view is on screen.
     */
    private fun updateAnimators() {
        val spinning = ring.spinning && isAttachedToWindow
        if (spinning) {
            if (!spinAnimator.isStarted) spinAnimator.start()
        } else {
            spinAnimator.cancel()
        }

        val breathing = OverlayStateLogic.showsBreathingDot(visual) && isAttachedToWindow
        if (breathing) {
            if (!breatheAnimator.isStarted) breatheAnimator.start()
        } else {
            breatheAnimator.cancel()
            dotAlpha = 1f
        }
    }
}
