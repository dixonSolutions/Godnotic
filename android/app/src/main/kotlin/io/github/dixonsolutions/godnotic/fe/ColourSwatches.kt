package io.github.dixonsolutions.godnotic.fe

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * The `colour` control (docs/UI_BRIDGE.md 4.5): one round swatch per palette entry, the
 * current value ringed, an optional short label (`labels`) written on each. A tap sends
 * the entry's `#rrggbb`.
 */
object ColourSwatches {
    fun view(ctx: Context, palette: List<String>, labels: List<String>, current: String, onPick: (String) -> Unit): View {
        val gap = Theme.dp(ctx, 10f)
        val size = Theme.dp(ctx, 44f)
        val grid = FlowGrid(ctx, 0, gap, wrap = true)
        palette.forEachIndexed { i, raw ->
            val hex = raw.lowercase()
            val colour = try { Color.parseColor(hex) } catch (e: IllegalArgumentException) { return@forEachIndexed }
            val on = hex == current.lowercase()
            val dark = (0.299 * Color.red(colour) + 0.587 * Color.green(colour) + 0.114 * Color.blue(colour)) < 140
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colour)
                setStroke(Theme.dp(ctx, if (on) 4f else 2f), if (on) Theme.EMBER else 0x59808080)
            }
            val cell = FrameLayout(ctx).apply {
                background = bg
                minimumWidth = size
                minimumHeight = size
                isClickable = true
                isFocusable = true
                contentDescription = labels.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { "Colour $it" } ?: "Colour $hex"
                setOnClickListener { Kit.haptic(it); onPick(hex) }
            }
            labels.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                cell.addView(Kit.text(ctx, it, Theme.SP_BODY, if (dark) Color.WHITE else Color.BLACK, bold = true).apply { gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            grid.addView(cell)
        }
        return grid
    }
}
