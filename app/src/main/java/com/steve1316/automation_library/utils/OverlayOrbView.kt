package com.steve1316.automation_library.utils

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The round overlay orb. Draws the glass circle, the progress ring, the center glyph or step number, and the breathing dot for the current state.
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

        /** Space around the orb for its shadow, in dp. */
        const val SHADOW_PAD_DP = 6f
    }

    private val totalSizePx = orbSizePx + shadowPadPx * 2

    // Pixels per design unit, so a 50dp orb draws everything 25% larger than the 40dp mockup.
    private val unit = orbSizePx / BASE_SIZE_DP
    private val arcRect = RectF()
    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = OverlayColors.ORB_FILL
            setShadowLayer(4f * unit, 0f, 2f * unit, OverlayColors.SHADOW)
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
            strokeWidth = 3f * unit
            color = OverlayColors.RING_TRACK
        }
    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * unit
            strokeCap = Paint.Cap.ROUND
        }
    private val turnPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = OverlayColors.WHITE
            textSize = 13f * unit
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OverlayColors.GREEN }
    private val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OverlayColors.ORB_FILL }

    private var style = OverlayStyle.TRAY
    private var visual = OverlayVisual.READY
    private var snapshot = BotStatus.Snapshot()
    private var ring = OverlayStateLogic.ringFor(visual, snapshot)
    private var turnText = ""
    private var hasRendered = false
    private var spinDegrees = 0f
    private var dotAlpha = 1f

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
     * Updates what the orb shows.
     *
     * @param style The overlay style.
     * @param visual What the overlay is showing.
     * @param snapshot The current run status.
     */
    fun render(style: OverlayStyle, visual: OverlayVisual, snapshot: BotStatus.Snapshot) {
        if (hasRendered && this.style == style && this.visual == visual && this.snapshot == snapshot) return
        hasRendered = true
        this.style = style
        this.visual = visual
        this.snapshot = snapshot
        ring = OverlayStateLogic.ringFor(visual, snapshot)
        turnText = snapshot.current.toString()
        val description = OverlayStateLogic.describe(style, visual)
        if (contentDescription != description) contentDescription = description
        updateAnimators()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(totalSizePx, totalSizePx)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimators()
    }

    override fun onDetachedFromWindow() {
        spinAnimator.cancel()
        breatheAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val center = totalSizePx / 2f
        val radius = orbSizePx / 2f
        canvas.drawCircle(center, center, radius, fillPaint)
        canvas.drawCircle(center, center, radius - unit / 2f, edgePaint)

        // The ring sits on the orb's edge.
        val inset = 1.5f * unit
        arcRect.set(center - radius + inset, center - radius + inset, center + radius - inset, center + radius - inset)
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)
        if (ring.fraction > 0f) {
            ringPaint.color = ring.color
            canvas.drawArc(arcRect, -90f, 360f * ring.fraction, false, ringPaint)
        }
        if (ring.spinning) {
            ringPaint.color = OverlayColors.SPINNER
            canvas.drawArc(arcRect, spinDegrees - 90f, 360f * 0.22f, false, ringPaint)
        }

        val glyph = OverlayStateLogic.orbCenterFor(style, visual, snapshot)
        if (glyph == null) {
            val baseline = center - (turnPaint.descent() + turnPaint.ascent()) / 2f
            canvas.drawText(turnText, center, baseline, turnPaint)
        } else {
            OverlayGlyphs.draw(canvas, glyph, center, center, 20f * unit, OverlayStateLogic.glyphColorFor(visual))
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
     * Runs only the animations the current state needs, and only while the view is on screen.
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
