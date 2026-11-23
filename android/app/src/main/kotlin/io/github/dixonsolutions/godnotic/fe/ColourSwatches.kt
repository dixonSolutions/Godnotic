package io.github.dixonsolutions.godnotic.fe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * The `colour` control (docs/UI_BRIDGE.md 4.5): one fully drawn round chip per palette
 * entry, the current value ringed, an optional short label on each. A tap sends the
 * entry's `#rrggbb`. Drawn on one canvas with its own wrap, so a chip is never clipped
 * by a row height (the old per-chip views were, in the owner's landscape screenshot).
 */
class ChipRow(
    ctx: Context, private val palette: List<String>, private val labels: List<String>,
    private var current: String, private val diameterDp: Float = 44f, private val onPick: (Int, String) -> Unit,
) : View(ctx) {
    private val d = Theme.dp(ctx, diameterDp)
    private val gap = Theme.dp(ctx, 10f)
    private val ring = Theme.dp(ctx, 4f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = Theme.dp(ctx, 16f).toFloat() }
    private var cols = 1

    init { isClickable = true; isFocusable = true; contentDescription = "Colours" }

    fun select(hex: String) { current = hex; invalidate() }

    private fun colours(): Int = ((palette.size + cols - 1) / cols)

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w).let { if (it == 0) d * 6 else it }
        val cell = d + 2 * ring
        cols = maxOf(1, (width + gap) / (cell + gap))
        val rows = colours()
        setMeasuredDimension(width, rows * cell + (rows - 1) * gap)
    }

    private fun centre(i: Int): Pair<Float, Float> {
        val cell = d + 2 * ring
        val r = i / cols; val c = i % cols
        return Pair(c * (cell + gap) + cell / 2f, r * (cell + gap) + cell / 2f)
    }

    override fun onDraw(cv: Canvas) {
        palette.forEachIndexed { i, hex ->
            val col = try { Color.parseColor(hex) } catch (e: IllegalArgumentException) { return@forEachIndexed }
            val (x, y) = centre(i)
            val on = hex.equals(current, true)
            fill.color = col
            cv.drawCircle(x, y, d / 2f, fill)
            edge.strokeWidth = Theme.dp(context, 2f).toFloat()
            edge.color = 0x99B0B8C8.toInt()
            cv.drawCircle(x, y, d / 2f, edge)
            if (on) {
                edge.strokeWidth = ring.toFloat() * 0.75f
                edge.color = Theme.EMBER
                cv.drawCircle(x, y, d / 2f + ring * 0.6f, edge)
            }
            labels.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                val dark = (0.299 * Color.red(col) + 0.587 * Color.green(col) + 0.114 * Color.blue(col)) < 140
                txt.color = if (dark) Color.WHITE else Color.BLACK
                cv.drawText(it, x, y - (txt.ascent() + txt.descent()) / 2f, txt)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        val r = d / 2f + ring + gap / 2f
        for (i in palette.indices) {
            val (x, y) = centre(i)
            if ((e.x - x) * (e.x - x) + (e.y - y) * (e.y - y) <= r * r) {
                Kit.haptic(this); performClick(); current = palette[i].lowercase(); invalidate(); onPick(i, palette[i].lowercase())
                break
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

object ColourSwatches {
    fun view(ctx: Context, palette: List<String>, labels: List<String>, current: String, onPick: (String) -> Unit): View =
        ChipRow(ctx, palette, labels, current) { _, hex -> onPick(hex) }
}
