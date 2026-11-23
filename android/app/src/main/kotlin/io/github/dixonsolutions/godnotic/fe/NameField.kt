package io.github.dixonsolutions.godnotic.fe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Editable
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The player-name field's extras (the `field` node with `kind: "name"`, docs/UI_BRIDGE.md 4.5):
 * the text shown in the colours its codes give, and the character keypad (Colours, Faces, Symbols)
 * that types into the same field as the system keyboard. The text maths is NameColour (JVM tests).
 */
object NameField {
    /** The keypad is shown or hidden for good: a per-device preference, opened the first time. */
    private const val PREFS = "fe-ui"
    private const val KEY_OPEN = "name_keypad_open"
    fun keypadOpen(ctx: Context): Boolean = try { ctx.getSharedPreferences(PREFS, 0).getBoolean(KEY_OPEN, true) } catch (e: Exception) { true }
    fun setKeypadOpen(ctx: Context, open: Boolean) { try { ctx.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_OPEN, open).apply() } catch (e: Exception) { /* a convenience */ } }

    /** Marks the spans this object put on a field, so restyling leaves the keyboard's own (composing) spans alone. */
    private interface Mine
    private class Tint(color: Int) : ForegroundColorSpan(color), Mine
    private class Backing(color: Int) : BackgroundColorSpan(color), Mine

    /** One game-font glyph, drawn as the game draws it (NameColour.glyphLook): the letter of the Quake charset, gold in its alternate half. */
    private class GlyphSpan(private val look: NameColour.GlyphLook, private val color: Int) : ReplacementSpan(), Mine {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = paint.measureText(look.text).toInt() + 2
        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val old = paint.color; val bold = paint.isFakeBoldText
            paint.color = if (look.gold) GOLD else color
            paint.isFakeBoldText = look.gold
            canvas.drawText(look.text, x + 1f, y.toFloat(), paint)
            paint.color = old; paint.isFakeBoldText = bold
        }
    }

    private const val GOLD = 0xFFFFB84A.toInt()
    private const val BACKING = 0xFF8A97B3.toInt()

    /** Colours the field's text: codes dimmed in their own colour, letters in the colour in force, dark colours on a light backing. */
    fun style(s: Editable) {
        for (sp in s.getSpans(0, s.length, Mine::class.java)) s.removeSpan(sp)
        val text = s.toString()
        for (seg in NameColour.segments(text)) {
            val rgb = Color.parseColor(seg.hex)
            val flags = Editable.SPAN_EXCLUSIVE_EXCLUSIVE
            when (seg.kind) {
                NameColour.Kind.CODE -> s.setSpan(Tint(if (NameColour.needsBacking(seg.hex)) Theme.TEXT_DIM else (rgb and 0x00FFFFFF) or 0x99000000.toInt()), seg.start, seg.end, flags)
                NameColour.Kind.TEXT -> {
                    s.setSpan(Tint(rgb or 0xFF000000.toInt()), seg.start, seg.end, flags)
                    if (NameColour.needsBacking(seg.hex)) s.setSpan(Backing(BACKING), seg.start, seg.end, flags)
                }
                NameColour.Kind.GLYPH -> NameColour.glyphLook(text[seg.start])?.let {
                    s.setSpan(GlyphSpan(it, rgb or 0xFF000000.toInt()), seg.start, seg.end, flags)
                }
            }
        }
    }

    /** The keypad: three groups behind a segmented control, keys of at least 48 dp, three rows high and scrolling. */
    fun keypad(ctx: Context, groups: List<Map<String, Any?>>, insert: (String) -> Unit): View {
        val root = Kit.column(ctx, 8f)
        if (groups.isEmpty()) return root
        val dm = ctx.resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        val cols = ((widthDp - 64f) / 52f).toInt().coerceIn(5, 14)
        val holder = ScrollView(ctx).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(ctx, 3 * 52f + 8f))
        }
        fun show(id: String) {
            val g = groups.firstOrNull { it.str("id") == id } ?: groups.first()
            val grid = GridLayout(ctx).apply { columnCount = cols; setPadding(Theme.dp(ctx, 2f), Theme.dp(ctx, 2f), Theme.dp(ctx, 2f), Theme.dp(ctx, 2f)) }
            for (k in g["keys"].list()) {
                val key = k.obj()
                val text = key.str("text")
                if (text.isEmpty()) continue
                val v = if (key.str("colour").isNotEmpty()) swatch(ctx, text, key.str("colour"), insert) else glyph(ctx, text, insert)
                val lp = GridLayout.LayoutParams().apply { width = Theme.dp(ctx, 48f); height = Theme.dp(ctx, 48f); setMargins(Theme.dp(ctx, 2f), Theme.dp(ctx, 2f), Theme.dp(ctx, 2f), Theme.dp(ctx, 2f)) }
                grid.addView(v, lp)
            }
            holder.removeAllViews(); holder.addView(grid); holder.scrollTo(0, 0)
        }
        if (groups.size > 1) {
            val bar = Kit.segmented(ctx, groups.map { it.str("id") to it.str("label", it.str("id")) }, groups.first().str("id")) { show(it) }
            root.addView(bar)
        }
        root.addView(holder)
        show(groups.first().str("id"))
        return root
    }

    /** A colour key: a round swatch of its colour, the code's digit on it (none for the ^xRGB ones); a tap types the code. */
    private fun swatch(ctx: Context, code: String, hex: String, insert: (String) -> Unit): View {
        val fill = Color.parseColor(hex)
        val t = TextView(ctx).apply {
            text = if (code.startsWith("^x")) "" else code.removePrefix("^")   // the six free colours are plain swatches: no letter that reads as a close button
            gravity = Gravity.CENTER
            textSize = Theme.SP_CAPTION
            typeface = Theme.bold
            setTextColor(if (NameColour.needsBacking(hex)) Theme.TEXT else 0xCC000000.toInt())
            background = Theme.rounded(fill, Theme.dp(ctx, 24f).toFloat(), Theme.dp(ctx, 2f), Theme.OUTLINE)
            contentDescription = "Colour $code"
            isFocusable = false
            setOnClickListener { Kit.haptic(it); insert(code) }
        }
        return FrameLayout(ctx).apply { addView(t, FrameLayout.LayoutParams(Theme.dp(ctx, 40f), Theme.dp(ctx, 40f), Gravity.CENTER)); isFocusable = false; setOnClickListener { t.performClick() } }
    }

    /** A character key, drawn as the game draws it: the game font's own glyphs in the gold of the Quake charset's alternate half. */
    private fun glyph(ctx: Context, text: String, insert: (String) -> Unit): View = TextView(ctx).apply {
        val look = if (NameColour.isGlyph(text)) NameColour.glyphLook(text[0]) else null
        this.text = look?.text ?: text
        gravity = Gravity.CENTER
        textSize = 22f
        typeface = if (look?.gold == true) Theme.bold else typeface
        setTextColor(if (look?.gold == true) GOLD else Theme.TEXT)
        background = Theme.surface(ctx, 0xFF060E1F.toInt(), 12f)
        contentDescription = "Insert $text"
        isFocusable = false
        setOnClickListener { Kit.haptic(it); insert(text) }
    }

    /** The keyboard icon beside the field: shows or hides the keypad and remembers which. */
    fun toggle(ctx: Context, open: Boolean, onToggle: (Boolean) -> Unit): TextView {
        var state = open
        val t = TextView(ctx)
        fun paint() {
            val d = Icons.drawable("keyboard", Theme.dp(ctx, 26f), if (state) Theme.EMBER else Theme.TEXT_MUTED)
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null)
            t.contentDescription = if (state) "Hide the character keyboard" else "Show the character keyboard"
            t.background = Theme.surface(ctx, if (state) 0xFF15306A.toInt() else 0xFF060E1F.toInt(), 12f)
        }
        t.apply {
            gravity = Gravity.CENTER
            val p = Theme.dp(ctx, 11f)
            setPadding(p, p, p, p)
            minimumWidth = Theme.dp(ctx, 48f); minimumHeight = Theme.dp(ctx, 48f)
            isFocusable = false
            setOnClickListener { state = !state; paint(); onToggle(state) }
        }
        paint()
        return t
    }
}
