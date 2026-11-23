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
class TagRow(ctx: Context, cells: List<String>, textColor: Int, private val table: Boolean = false) : LinearLayout(ctx) {
    private val views = ArrayList<TextView>()
    private var sp = 0f

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = Theme.dp(ctx, if (table) 8f else 6f)
        for ((i, c) in cells.withIndex()) {
            if (i > 0 && !table) {
                val bar = View(ctx)
                bar.setBackgroundColor((Theme.BLUE and 0x00FFFFFF) or 0x66000000)
                addView(bar, LayoutParams(Math.max(1, Theme.dp(ctx, 1f)), Theme.dp(ctx, 14f)))
            }
            val t = Kit.text(ctx, c, CardTags.sizeFor(0, 1f), textColor, lines = 1)
            t.ellipsize = TextUtils.TruncateAt.END
            if (table) {
                // a server line: the name takes most of the width, the map less, the players
                // (the last cell) only what they need, flush with the card's right padding
                val first = i == 0
                val last = i == cells.size - 1 && cells.size > 1
                t.setPadding(if (first) 0 else pad, 0, if (last) 0 else pad, 0)
                t.gravity = Gravity.CENTER_VERTICAL or (if (last) Gravity.END else Gravity.START)
                if (last) t.maxLines = 1
                views.add(t)
                addView(t, if (last) LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    else LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, if (first && cells.size > 1) CardTags.NAME_WEIGHT else if (first) 1f else CardTags.MAP_WEIGHT))
            } else {
                t.setPadding(pad, 0, pad, 0)
                t.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                views.add(t)
                addView(t, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
    }

    val cellCount: Int get() = views.size

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val next = if (table) CardTags.tableSizeFor(MeasureSpec.getSize(wSpec), resources.displayMetrics.density)
            else CardTags.sizeFor(MeasureSpec.getSize(wSpec), resources.displayMetrics.density)
        if (next != sp) { sp = next; for (t in views) t.textSize = next }
        super.onMeasure(wSpec, hSpec)
    }
}

/**
 * A `display: table` card's server lines, as many as fit the height the card gives them (never a
 * clipped half line): every line is [CardTags.tableRowDp] tall, the rest are hidden. Without a
 * bounded height (a card in an ordinary column) it shows [CardTags.UNBOUNDED_ROWS] lines.
 */
class TableRows(ctx: Context) : LinearLayout(ctx) {
    init { orientation = VERTICAL }

    /** How many lines the last measure showed. */
    var shown = 0
        private set

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val d = resources.displayMetrics.density
        val natural = Math.round(CardTags.tableRowDp(MeasureSpec.getSize(wSpec) / d) * d)
        val unbounded = MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED
        val avail = if (unbounded) natural * CardTags.UNBOUNDED_ROWS else MeasureSpec.getSize(hSpec)
        val rowPx = if (unbounded) natural else CardTags.rowPxFor(avail, natural, d)
        shown = CardTags.fitRows(avail, rowPx, childCount)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.visibility = if (i < shown) View.VISIBLE else View.GONE
            (c.layoutParams as? LayoutParams)?.height = rowPx
        }
        super.onMeasure(wSpec, hSpec)
    }
}

object CardTags {
    /** The most server lines a table card keeps (the QuakeC side sends at most this many). */
    const val MAX_ROWS = 12
    /** Lines shown when the height is unbounded. */
    const val UNBOUNDED_ROWS = 6
    /** Name : map width shares of a server line; the players take what they need. */
    const val NAME_WEIGHT = 5f
    const val MAP_WEIGHT = 3f

    /** The text size (sp) of a server line on a card [widthPx] wide: bigger than a tag, readable on a tablet. */
    fun tableSizeFor(widthPx: Int, density: Float): Float {
        if (widthPx <= 0) return 16f
        val dp = widthPx / density
        return if (dp >= 440f) 18f else if (dp >= 320f) 16f else if (dp >= 220f) 14f else 12f
    }

    /** The height (dp) of one server line on a card [widthDp] wide. */
    fun tableRowDp(widthDp: Float): Float = if (widthDp >= 440f) 52f else if (widthDp >= 320f) 44f else if (widthDp >= 220f) 36f else 32f

    /** The shortest a server line gets (dp), when the card is too short for [WANTED_ROWS] at their usual height. */
    const val MIN_ROW_DP = 30f
    /** Lines the card tries to show before shrinking their height. */
    const val WANTED_ROWS = 10

    /** A line's height (px): [naturalPx], or less (down to [MIN_ROW_DP]) so that [WANTED_ROWS] lines fit [availPx]. */
    fun rowPxFor(availPx: Int, naturalPx: Int, density: Float): Int {
        if (availPx / WANTED_ROWS >= naturalPx) return naturalPx
        return maxOf(Math.round(MIN_ROW_DP * density), availPx / WANTED_ROWS).coerceAtMost(naturalPx)
    }

    /** How many whole lines of [rowPx] fit [availPx] (at most [have]; 0 when not even one fits). */
    fun fitRows(availPx: Int, rowPx: Int, have: Int): Int =
        if (rowPx <= 0) 0 else minOf(maxOf(0, have), MAX_ROWS, maxOf(0, availPx) / rowPx)

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
        if (p["featured"] == true && p["display"] != "table" && p["live"] != true && !(p["image"] as? String).isNullOrEmpty()) {
            val s = p["subtitle"] as? String ?: ""
            if (s.contains(" · ") && !s.contains('\n')) return s.split(" · ").take(6)
        }
        return emptyList()
    }

    /**
     * A card's lines of cells (at most [MAX_ROWS] lines of four): a `display: table` card's `rows`; for an
     * older payload of a featured live card (or one with no picture) and no `tags` (Home's Multiplayer card, whose
     * servers are the lines of its `subtitle`), each line split at " · " into name, map, players.
     */
    fun rows(p: Map<String, Any?>): List<List<String>> {
        if (p["display"] == "table") {
            return (p["rows"] as? List<*>).orEmpty().take(MAX_ROWS).mapNotNull { r ->
                (r as? List<*>)?.take(4)?.map { it?.toString() ?: "" }?.takeIf { it.isNotEmpty() }
            }
        }
        if (p["featured"] == true && (p["live"] == true || (p["image"] as? String).isNullOrEmpty()) && ((p["tags"] as? List<*>).isNullOrEmpty())) {
            return (p["subtitle"] as? String ?: "").lines().filter { it.contains(" · ") }.take(MAX_ROWS).map { line ->
                // the last two parts are the map and the players; a name may hold " · " itself
                val parts = line.split(" · ")
                if (parts.size <= 3) parts else listOf(parts.dropLast(2).joinToString(" · "), parts[parts.size - 2], parts.last())
            }
        }
        return emptyList()
    }

    fun tagged(p: Map<String, Any?>) = of(p).isNotEmpty() || rows(p).isNotEmpty() || p["display"] == "table"
}
