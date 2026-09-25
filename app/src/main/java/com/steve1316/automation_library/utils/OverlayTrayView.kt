package com.steve1316.automation_library.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The overlay's mini tray: the run status on the left and its buttons on the right. It sizes itself from the orb so both scale together.
 *
 * @param context The service context.
 * @param orbSizePx Diameter of the orb in pixels. The tray is exactly this tall.
 * @param onButton Called when one of the tray's buttons is tapped.
 * @param onInteract Called when the user touches the tray, so its auto-close can restart.
 * @param onOutsideTouch Called when the user touches anywhere outside the tray.
 */
@SuppressLint("ViewConstructor")
internal class OverlayTrayView(
    context: Context,
    private val orbSizePx: Int,
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
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
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
    private val buttonRow =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
    private var shownButtons: List<TrayButton> = emptyList()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = (4f * unit).roundToInt()
        setPadding(pad, 0, pad, 0)
        background =
            GradientDrawable().apply {
                cornerRadius = orbSizePx / 2f
                setColor(OverlayColors.ORB_FILL)
                setStroke(unit.roundToInt().coerceAtLeast(1), OverlayColors.ORB_STROKE)
            }
        val readout =
            LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(pad, 0, (6f * unit).roundToInt(), 0)
                addView(headline)
                addView(detail)
            }
        addView(readout)
        addView(buttonRow)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The tray is always exactly as tall as the orb.
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(orbSizePx, MeasureSpec.EXACTLY))
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
     * Updates the tray's text and buttons.
     *
     * @param visual What the overlay is showing.
     * @param snapshot The current run status.
     * @param canPause True when the run has reached a safe point, so the Pause button is offered.
     */
    fun render(visual: OverlayVisual, snapshot: BotStatus.Snapshot, canPause: Boolean) {
        val line = OverlayStateLogic.headlineFor(visual, snapshot)
        headline.text =
            SpannableStringBuilder(line.text).apply {
                if (line.tag != null) {
                    append("  ")
                    val start = length
                    append(line.tag)
                    setSpan(ForegroundColorSpan(line.tagColor), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(RelativeSizeSpan(0.75f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        detail.text = OverlayStateLogic.trayDetailFor(visual, snapshot, BotHold.pauseReason)

        val buttons = OverlayStateLogic.trayButtonsFor(visual, canPause)
        if (buttons != shownButtons) {
            shownButtons = buttons
            buttonRow.removeAllViews()
            val size = (32f * unit).roundToInt()
            val gap = (5f * unit).roundToInt()
            buttons.forEachIndexed { index, button ->
                val view = OverlayTrayButtonView(context, button, size).apply { setOnClickListener { onButton(button) } }
                buttonRow.addView(view, LayoutParams(size, size).apply { if (index > 0) marginStart = gap })
            }
        }
    }
}

/**
 * One round tray button with its glyph.
 *
 * @param context The service context.
 * @param button Which button this is.
 * @param sizePx Diameter of the button in pixels.
 */
@SuppressLint("ViewConstructor")
private class OverlayTrayButtonView(context: Context, private val button: TrayButton, private val sizePx: Int) : View(context) {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = button.topColor }

    init {
        contentDescription = button.label
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        val center = sizePx / 2f
        canvas.drawCircle(center, center, center, backgroundPaint)
        OverlayGlyphs.draw(canvas, button.glyph, center, center, sizePx * 15f / 32f, OverlayColors.WHITE)
    }
}
