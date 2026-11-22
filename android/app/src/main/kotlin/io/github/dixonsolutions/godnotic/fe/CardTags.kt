package io.github.dixonsolutions.godnotic.fe

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A card's details (UI bridge card `tags`, or one `rows` line of a `display: table` card) as one
 * line of equal-width cells with a separator between them. Every cell is one line ending in an
 * ellipsis, never wrapped; the text steps down on a narrow card (a third of a phone) so a cell
 * keeps a few letters.
 */
class TagRow(ctx: Context, cells: List<String>, textColor: Int) : LinearLayout(ctx) {
    private val views = ArrayList<TextView>()
    private var sp = 0f

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = Theme.dp(ctx, 6f)
        for ((i, c) in cells.withIndex()) {
            if (i > 0) {
                val bar = View(ctx)
                bar.setBackgroundColor((Theme.BLUE and 0x00FFFFFF) or 0x66000000)
                addView(bar, LayoutParams(Math.max(1, Theme.dp(ctx, 1f)), Theme.dp(ctx, 14f)))
            }
            val t = Kit.text(ctx, c, CardTags.sizeFor(0, 1f), textColor, lines = 1)
            t.ellipsize = TextUtils.TruncateAt.END
            t.setPadding(pad, 0, pad, 0)
            t.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            views.add(t)
            addView(t, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    val cellCount: Int get() = views.size

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val next = CardTags.sizeFor(MeasureSpec.getSize(wSpec), resources.displayMetrics.density)
        if (next != sp) { sp = next; for (t in views) t.textSize = next }
        super.onMeasure(wSpec, hSpec)
    }
}

object CardTags {
    /** The cell text size (sp) for a row [widthPx] wide (0: unknown, the roomy size). */
    fun sizeFor(widthPx: Int, density: Float): Float {
        if (widthPx <= 0) return 13f
        val dp = widthPx / density
        return if (dp >= 320f) 13f else if (dp >= 220f) 11.5f else 10f
    }

    /**
     * What a card shows as its line of details: `tags`; for a featured card of an older payload that
     * only has a `subtitle`, its " · "-separated parts. A `display: table` card shows `rows` instead.
     */
    fun of(p: Map<String, Any?>): List<String> {
        val tags = (p["tags"] as? List<*>)?.mapNotNull { (it as? String)?.takeIf { s -> s.isNotEmpty() } } ?: emptyList()
        if (tags.isNotEmpty()) return tags.take(6)
        if (p["featured"] == true && p["display"] != "table" && !(p["image"] as? String).isNullOrEmpty()) {
            val s = p["subtitle"] as? String ?: ""
            if (s.contains(" · ")) return s.split(" · ").take(6)
        }
        return emptyList()
    }

    /**
     * A card's lines of cells (at most six lines of four): a `display: table` card's `rows`; for an
     * older payload of a featured card with no picture and no `tags` (Home's Multiplayer card, whose
     * servers are the lines of its `subtitle`), each line split at " · " into name, map, players.
     */
    fun rows(p: Map<String, Any?>): List<List<String>> {
        if (p["display"] == "table") {
            return (p["rows"] as? List<*>).orEmpty().take(6).mapNotNull { r ->
                (r as? List<*>)?.take(4)?.map { it?.toString() ?: "" }?.takeIf { it.isNotEmpty() }
            }
        }
        if (p["featured"] == true && (p["image"] as? String).isNullOrEmpty() && ((p["tags"] as? List<*>).isNullOrEmpty())) {
            return (p["subtitle"] as? String ?: "").lines().filter { it.contains(" · ") }.take(6).map { line ->
                // the last two parts are the map and the players; a name may hold " · " itself
                val parts = line.split(" · ")
                if (parts.size <= 3) parts else listOf(parts.dropLast(2).joinToString(" · "), parts[parts.size - 2], parts.last())
            }
        }
        return emptyList()
    }

    fun tagged(p: Map<String, Any?>) = of(p).isNotEmpty() || rows(p).isNotEmpty() || p["display"] == "table"
}
