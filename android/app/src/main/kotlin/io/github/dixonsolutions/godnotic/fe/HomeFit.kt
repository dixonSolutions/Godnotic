package io.github.dixonsolutions.godnotic.fe

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.max
import kotlin.math.min

/** Where Home's pieces go in a box: pure arithmetic, so a unit test can check every screen shape. */
object HomeFit {
    /** All sizes in px. [top] is the offset of the banner inside the box (the group is centred). */
    data class Plan(val stack: Boolean, val bannerH: Int, val tilesTop: Int, val tilesH: Int, val top: Int, val cardH: Int = tilesH, val mpH: Int = tilesH)

    /** The Godnotic banner's picture is 3:1. */
    const val BANNER_ASPECT = 3f

    /** In landscape the banner never takes more than this share of the height (the cards need the rest). */
    const val LANDSCAPE_BANNER = 0.20f

    /** True when a field of at least [minField] and chips of [chipsNatural] fit beside each other in [width]. */
    fun sideBySide(width: Int, minField: Int, chipsNatural: Int, gap: Int) = width >= minField + gap + chipsNatural

    /**
     * Every card, the server-list one included, is [Plan.cardH] tall: the server card shows the
     * lines that fit its share (see [TableRows]) rather than growing past its neighbours.
     */
    fun plan(w: Int, h: Int, gap: Int, tiles: Int): Plan {
        val portrait = w < h
        val n = max(1, tiles)
        val bannerH = min((w / BANNER_ASPECT).toInt(), (h * if (portrait) 0.24f else LANDSCAPE_BANNER).toInt())
        val room = max(0, h - bannerH - gap)
        val tilesH = if (portrait) {
            // stacked cards: wide, but never taller than 0.4 of the width each
            min(room, n * (w * 0.4f).toInt() + (n - 1) * gap)
        } else {
            // side by side: as tall as the room, never more than 1.25 times a card's width
            val cw = (w - gap * (n - 1)) / n
            min(room, (cw * 1.25f).toInt())
        }
        val total = bannerH + gap + tilesH
        return Plan(portrait, bannerH, bannerH + gap, tilesH, max(0, (h - total) / 2), tilesH, tilesH)
    }
}

/**
 * Home's body: the banner and the row (or, held upright, the stack) of cards are measured to the
 * space the screen has, with no scrolling, and the group sits in the middle of that space. Only
 * with [fit] set and an exact height (the page's scroller gives the child the viewport's height);
 * otherwise an ordinary column.
 */
class FitBody(ctx: Context, spacingPx: Int) : LinearLayout(ctx) {
    var fit = false
    private var plan: HomeFit.Plan? = null

    init {
        orientation = VERTICAL
        if (spacingPx > 0) {
            showDividers = SHOW_DIVIDER_MIDDLE
            dividerDrawable = android.graphics.drawable.ShapeDrawable().apply { intrinsicHeight = spacingPx; paint.color = android.graphics.Color.TRANSPARENT }
        }
    }

    private fun banner(): View? = (0 until childCount).map { getChildAt(it) }.firstOrNull { it is RoundImage && it.visibility != GONE }
    private fun grid(): FlowGrid? = (0 until childCount).map { getChildAt(it) }.firstOrNull { it is FlowGrid && it.visibility != GONE } as? FlowGrid

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val g = grid()
        if (!fit || g == null || MeasureSpec.getMode(hSpec) != MeasureSpec.EXACTLY) {
            plan = null
            super.onMeasure(wSpec, hSpec)
            // asked for its natural height (the scroller's first pass): short, so the scroller asks again with the viewport's
            if (fit && g != null) setMeasuredDimension(measuredWidth, 0)
            return
        }
        val w = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
        val h = MeasureSpec.getSize(hSpec) - paddingTop - paddingBottom
        val gap = max(dividerDrawable?.intrinsicHeight ?: 0, 1)
        val tiles = (0 until g.childCount).count { g.getChildAt(it).visibility != GONE }
        val p = HomeFit.plan(w, h, gap, tiles)
        plan = p
        g.fill = true
        g.stack = p.stack
        banner()?.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(p.bannerH, MeasureSpec.EXACTLY))
        g.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(p.tilesH, MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val p = plan
        val g = grid()
        if (!fit || p == null || g == null) { super.onLayout(changed, l, t, r, b); return }
        val x = paddingLeft
        val w = r - l - paddingLeft - paddingRight
        val y0 = paddingTop + p.top
        banner()?.layout(x, y0, x + w, y0 + p.bannerH)
        g.layout(x, y0 + p.tilesTop, x + w, y0 + p.tilesTop + p.tilesH)
    }
}

/**
 * A card's inner column. Given an exact size (Home's fitted cards) it fills it: in a wide cell the
 * picture on the left and the text beside it, in a tall one the text under a picture that takes
 * what the text leaves. Without one, the plain column of picture, bar and text.
 */
class CardColumn(ctx: Context, private val img: View, private val bar: View, private val text: View) : LinearLayout(ctx) {
    private var wide = false
    private var custom = false
    private var topText = false

    /** Home's cards: the text (title and details) on top, the picture and its bar under it. */
    var textFirst = false
        set(v) {
            if (field == v) return
            field = v
            removeAllViews()
            val order = if (v) listOf(text, img, bar) else listOf(img, bar, text)
            for (c in order) addView(c, c.layoutParams ?: LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            requestLayout()
        }

    init { orientation = VERTICAL }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        topText = false
        if (MeasureSpec.getMode(wSpec) != MeasureSpec.EXACTLY || MeasureSpec.getMode(hSpec) != MeasureSpec.EXACTLY || img.visibility == GONE) { custom = false; wide = false; super.onMeasure(wSpec, hSpec); return }
        custom = true
        val w = MeasureSpec.getSize(wSpec)
        val h = MeasureSpec.getSize(hSpec)
        val barH = if (bar.visibility == GONE) 0 else (bar.layoutParams?.height ?: 0)
        if (textFirst) {
            topText = true
            text.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
            img.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(max(0, h - barH - text.measuredHeight), MeasureSpec.EXACTLY))
            bar.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(barH, MeasureSpec.EXACTLY))
            setMeasuredDimension(w, h)
            return
        }
        wide = w > h * 1.3f
        if (wide) {
            val iw = (w * 0.42f).toInt()
            img.measure(MeasureSpec.makeMeasureSpec(iw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            text.measure(MeasureSpec.makeMeasureSpec(w - iw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h - barH, MeasureSpec.AT_MOST))
            bar.measure(MeasureSpec.makeMeasureSpec(w - iw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(barH, MeasureSpec.EXACTLY))
        } else {
            text.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h / 2, MeasureSpec.AT_MOST))
            bar.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(barH, MeasureSpec.EXACTLY))
            img.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(max(0, h - barH - text.measuredHeight), MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (!custom) { super.onLayout(changed, l, t, r, b); return }
        val w = r - l; val h = b - t
        if (topText) {
            text.layout(0, 0, w, text.measuredHeight)
            img.layout(0, text.measuredHeight, w, text.measuredHeight + img.measuredHeight)
            bar.layout(0, text.measuredHeight + img.measuredHeight, w, text.measuredHeight + img.measuredHeight + bar.measuredHeight)
        } else if (wide) {
            val iw = img.measuredWidth
            img.layout(0, 0, iw, h)
            val blockH = text.measuredHeight + bar.measuredHeight
            val y = max(0, (h - blockH) / 2)
            text.layout(iw, y, w, y + text.measuredHeight)
            bar.layout(iw, y + text.measuredHeight, w, y + blockH)
        } else {
            img.layout(0, 0, w, img.measuredHeight)
            bar.layout(0, img.measuredHeight, w, img.measuredHeight + bar.measuredHeight)
            text.layout(0, img.measuredHeight + bar.measuredHeight, w, h)
        }
    }
}

/**
 * A screen's toolbar (Multiplayer's search field and filter chips): side by side when the field
 * keeps [minField] px and the chips fit in one line beside it, otherwise stacked, the field on
 * top and the chips in one wrapping row under it. Re-decided on every measure, so a rotation
 * changes it live.
 */
class AdaptiveBar(ctx: Context, private val gap: Int, private val minField: Int) : LinearLayout(ctx) {
    private var lastSide: Boolean? = null

    init { gravity = android.view.Gravity.CENTER_VERTICAL }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val chips = (0 until childCount).map { getChildAt(it) }.firstOrNull { it is FlowGrid && it.visibility != GONE } as? FlowGrid
        val field = (0 until childCount).map { getChildAt(it) }.firstOrNull { it !== chips && it.visibility != GONE }
        if (chips == null || field == null) { orientation = HORIZONTAL; super.onMeasure(wSpec, hSpec); return }
        var natural = 0
        for (i in 0 until chips.childCount) {
            val c = chips.getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            natural += c.measuredWidth + gap
        }
        val width = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
        val side = HomeFit.sideBySide(width, minField, natural, gap)
        if (lastSide != side) {
            lastSide = side
            orientation = if (side) HORIZONTAL else VERTICAL
            field.layoutParams = LayoutParams(if (side) 0 else ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (side) 1f else 0f)
            chips.layoutParams = LayoutParams(if (side) 0 else ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (side) 1f else 0f).apply { if (side) marginStart = gap else topMargin = gap }
        }
        super.onMeasure(wSpec, hSpec)
    }
}
