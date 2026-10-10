package com.steve1316.automation_library.utils

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.style.ReplacementSpan
import kotlin.math.roundToInt

/**
 * The tray's glass background: a top-lit gradient pill with a thin top highlight, a hairline edge, and two shadow layers. It leaves `padPx`
 * on every side for the shadow, so the view using it needs that much padding and a software layer.
 *
 * @param unit Pixels per design unit, matching the orb.
 * @param padPx Room left around the pill for its shadow, in pixels.
 */
internal class GlassPillDrawable(private val unit: Float, private val padPx: Int) : Drawable() {
    private val pill = RectF()
    private val edge = RectF()
    private val softShadowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = OverlayColors.GLASS_BOTTOM
            setShadowLayer(5.5f * unit, 0f, 2f * unit, OverlayColors.SHADOW_SOFT)
        }
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

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        pill.set(bounds.left + padPx.toFloat(), bounds.top + padPx.toFloat(), bounds.right - padPx.toFloat(), bounds.bottom - padPx.toFloat())
        edge.set(pill)
        edge.inset(unit / 2f, unit / 2f)
        fillPaint.shader = LinearGradient(0f, pill.top, 0f, pill.bottom, OverlayColors.GLASS_TOP, OverlayColors.GLASS_BOTTOM, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val radius = pill.height() / 2f
        canvas.drawRoundRect(pill, radius, radius, softShadowPaint)
        canvas.drawRoundRect(pill, radius, radius, fillPaint)
        canvas.drawRoundRect(edge, radius, radius, edgePaint)
        // A straight highlight along the pill's flat top, like light on glass.
        val y = pill.top + 1.6f * unit
        canvas.drawLine(pill.left + radius, y, pill.right - radius, y, highlightPaint)
    }

    // The tray fades through its view alpha, so the drawable ignores alpha and color filters.
    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * A small rounded tag such as "PAUSED" drawn after the tray's headline: bold letter-spaced text in the state color on a faint tint of it.
 *
 * @param color The tag's text color.
 * @param unit Pixels per design unit, matching the orb.
 */
internal class PillTagSpan(color: Int, private val unit: Float) : ReplacementSpan() {
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = 6.5f * unit
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
        }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = OverlayColors.tintOf(color) }
    private val rect = RectF()
    private val gap = 5f * unit
    private val padX = 4f * unit
    private val padY = 2f * unit

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        (gap + padX * 2f + textPaint.measureText(text, start, end)).roundToInt()

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val width = textPaint.measureText(text, start, end)
        val centerY = (top + bottom) / 2f
        val half = (textPaint.descent() - textPaint.ascent()) / 2f
        rect.set(x + gap, centerY - half - padY, x + gap + width + padX * 2f, centerY + half + padY)
        canvas.drawRoundRect(rect, 4f * unit, 4f * unit, backgroundPaint)
        canvas.drawText(text, start, end, rect.left + padX, centerY - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
    }
}
