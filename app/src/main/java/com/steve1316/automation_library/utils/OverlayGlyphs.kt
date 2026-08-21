package com.steve1316.automation_library.utils

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Draws the overlay's glyphs using the 24-unit coordinates from the approved mockups. Only used on the main thread. */
internal object OverlayGlyphs {
    private val path = Path()
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2.6f
        }

    /**
     * Draws a glyph centered on a point.
     *
     * @param canvas The canvas to draw on.
     * @param glyph Which glyph to draw.
     * @param cx Center x in pixels.
     * @param cy Center y in pixels.
     * @param boxPx Side of the square the glyph fills, in pixels.
     * @param color The glyph's ARGB color.
     */
    fun draw(canvas: Canvas, glyph: Glyph, cx: Float, cy: Float, boxPx: Float, color: Int) {
        val scale = boxPx / 24f
        fill.color = color
        stroke.color = color
        canvas.save()
        canvas.translate(cx - boxPx / 2f, cy - boxPx / 2f)
        canvas.scale(scale, scale)
        when (glyph) {
            Glyph.PLAY -> {
                path.reset()
                path.moveTo(8.5f, 5.8f)
                path.lineTo(8.5f, 18.2f)
                path.lineTo(18.5f, 12f)
                path.close()
                canvas.drawPath(path, fill)
            }
            Glyph.STOP -> {
                rect.set(7f, 7f, 17f, 17f)
                canvas.drawRoundRect(rect, 2f, 2f, fill)
            }
            Glyph.PAUSE -> {
                rect.set(7f, 6f, 10.6f, 18f)
                canvas.drawRoundRect(rect, 1.2f, 1.2f, fill)
                rect.set(13.4f, 6f, 17f, 18f)
                canvas.drawRoundRect(rect, 1.2f, 1.2f, fill)
            }
            Glyph.CHECK -> {
                path.reset()
                path.moveTo(6f, 12.5f)
                path.lineTo(10f, 16.5f)
                path.lineTo(18f, 7.5f)
                canvas.drawPath(path, stroke)
            }
            Glyph.ALERT -> {
                canvas.drawLine(12f, 6.5f, 12f, 13.5f, stroke)
                canvas.drawCircle(12f, 17.5f, 1.5f, fill)
            }
        }
        canvas.restore()
    }
}
