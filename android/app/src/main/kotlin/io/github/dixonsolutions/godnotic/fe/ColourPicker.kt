package io.github.dixonsolutions.godnotic.fe

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar

/**
 * The player-name colour picker (the `colour` node with `picker: "name"`, docs/UI_BRIDGE.md 4.5).
 * Hue, saturation and brightness sliders make an `^xRGB` code; ten preset chips give ^0 to ^9;
 * "from" and "to" sliders choose which letters get it (all of them to begin with); a live
 * preview shows the name as it will look. "Apply" sends `name:<the new name>`; the QC side
 * checks it again. "Remove the colour codes" stays a separate, smaller button.
 */
object ColourPicker {
    fun view(ctx: Context, name: String, max: Int, onSend: (String) -> Unit): View {
        val dp = { v: Float -> Theme.dp(ctx, v) }
        val col = Kit.column(ctx, 10f)
        var hue = 20.0; var sat = 1.0; var bri = 1.0
        var from = 0; var to = NameColour.plain(name).length
        var picked: String? = null   // the code in force for Apply: a preset (^N) or the sliders' ^xRGB
        val plainLen = to

        val preview = Kit.text(ctx, "", Theme.SP_TITLE, Theme.TEXT, bold = true).apply {
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = GradientDrawable().apply { setColor(0xFF3A3F4A.toInt()); cornerRadius = dp(10f).toFloat() }
            minHeight = dp(52f)
        }
        val info = Kit.text(ctx, "", Theme.SP_CAPTION, Theme.TEXT_MUTED)
        val swatch = View(ctx)
        val code = Kit.text(ctx, "", Theme.SP_BODY, Theme.TEXT, bold = true)

        fun currentCode(): String = picked ?: NameColour.xonCode(NameColour.hsvToHex(hue, sat, bri))
        fun currentHex(): String {
            val c = currentCode()
            return if (c.startsWith("^x")) NameColour.xonHex(c) else NameColour.PRESETS[c[1] - '0']
        }
        fun result(): String? = if (plainLen == 0) null else NameColour.paint(name, from, to, currentCode(), max)
        fun refresh() {
            val hex = currentHex()
            swatch.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(hex)); setStroke(dp(2f), 0x99B0B8C8.toInt()) }
            code.text = currentCode()
            val shown = result() ?: name
            val sb = SpannableStringBuilder()
            val chars = NameColour.parse(shown)
            // the part being coloured is lit with a lighter background
            val inRange = result()?.let { NameColour.parse(it) } ?: chars
            for ((i, ch) in chars.withIndex()) {
                val s = sb.length; sb.append(ch.c)
                val hx = when {
                    ch.code == null -> null
                    ch.code.startsWith("^x") -> NameColour.xonHex(ch.code)
                    else -> NameColour.PRESETS[ch.code[1] - '0']
                }
                sb.setSpan(ForegroundColorSpan(hx?.let { Color.parseColor(it) } ?: Color.WHITE), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (i in from until to && inRange.size == chars.size) sb.setSpan(BackgroundColorSpan(0x55FFFFFF), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (sb.isEmpty()) sb.append(" ")
            preview.text = sb
            val len = (result() ?: name).length
            info.text = if (result() == null && plainLen > 0) "Too long: the name with its colour codes would pass $max characters"
                        else "$len of $max characters, codes included"
            info.setTextColor(if (result() == null && plainLen > 0) Theme.WARN else Theme.TEXT_MUTED)
        }

        fun bar(maxV: Int, start: Int, bg: Drawable?, onChange: (Int) -> Unit): SeekBar = SeekBar(ctx).apply {
            this.max = maxV; progress = start
            if (bg != null) { background = bg; progressDrawable = ColorDrawable(Color.TRANSPARENT) }
            minimumHeight = dp(40f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) onChange(p) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        fun gradient(vararg c: Int) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, c).apply { cornerRadius = dp(8f).toFloat() }

        var chips: ChipRow? = null
        var satBar: SeekBar? = null; var briBar: SeekBar? = null
        fun tint() {
            satBar?.background = gradient(Color.parseColor(NameColour.hsvToHex(hue, 0.0, bri)), Color.parseColor(NameColour.hsvToHex(hue, 1.0, bri)))
            briBar?.background = gradient(Color.BLACK, Color.parseColor(NameColour.hsvToHex(hue, sat, 1.0)))
        }

        col.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(info)

        if (plainLen > 1) {
            col.addView(Kit.text(ctx, "Colour these letters", Theme.SP_CAPTION, Theme.TEXT_MUTED))
            val fromBar = bar(plainLen - 1, 0, null) { p -> from = p; if (to <= from) to = from + 1; refresh() }
            val toBar = bar(plainLen, plainLen, null) { p -> to = maxOf(p, 1); if (from >= to) from = to - 1; refresh() }
            fromBar.contentDescription = "First letter to colour"; toBar.contentDescription = "Last letter to colour"
            val rr = Kit.row(ctx, 8f)
            rr.addView(Kit.text(ctx, "From", Theme.SP_CAPTION, Theme.TEXT_MUTED))
            rr.addView(fromBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            rr.addView(Kit.text(ctx, "To", Theme.SP_CAPTION, Theme.TEXT_MUTED))
            rr.addView(toBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(rr)
        }

        val hueBar = bar(359, hue.toInt(), gradient(*IntArray(7) { Color.parseColor(NameColour.hsvToHex(it * 60.0, 1.0, 1.0)) })) { p -> hue = p.toDouble(); picked = null; chips?.select(""); tint(); refresh() }
        satBar = bar(100, 100, null) { p -> sat = p / 100.0; picked = null; chips?.select(""); tint(); refresh() }
        briBar = bar(100, 100, null) { p -> bri = p / 100.0; picked = null; chips?.select(""); tint(); refresh() }
        hueBar.contentDescription = "Hue"; satBar.contentDescription = "Saturation"; briBar.contentDescription = "Brightness"
        for ((label, b) in listOf("Hue" to hueBar, "Saturation" to satBar, "Brightness" to briBar)) {
            val r = Kit.row(ctx, 8f)
            r.addView(Kit.text(ctx, label, Theme.SP_CAPTION, Theme.TEXT_MUTED).apply { minWidth = dp(92f) })
            r.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(r)
        }
        tint()

        col.addView(Kit.text(ctx, "Presets (^0 to ^9)", Theme.SP_CAPTION, Theme.TEXT_MUTED))
        chips = ChipRow(ctx, NameColour.PRESETS, List(10) { it.toString() }, "", 36f) { i, _ -> picked = NameColour.presetCode(i); refresh() }
        col.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val now = Kit.row(ctx, 10f)
        now.addView(swatch, LinearLayout.LayoutParams(dp(28f), dp(28f)))
        now.addView(code)
        col.addView(now)

        col.addView(Kit.button(ctx, "Apply the colour", primary = true) { result()?.let(onSend) }.apply { contentDescription = "Apply the colour to the chosen letters" })
        refresh()
        return col
    }
}
