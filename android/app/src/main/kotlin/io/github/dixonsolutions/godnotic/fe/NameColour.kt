package io.github.dixonsolutions.godnotic.fe

import kotlin.math.roundToInt

/**
 * The text of a player name as the Name field shows and edits it: Xonotic colour codes
 * (`^0`..`^9`, `^xRGB`, `^^` for a caret) and the game font's private glyphs (U+E0xx, the Quake
 * charset page DarkPlaces draws from its bitmap font). Plain Kotlin, no Android, so the JVM tests
 * cover it; NameField.kt turns the result into spans. The QC side (touch_uib_player.qc) checks
 * every name it receives.
 */
object NameColour {
    /** The engine takes names of up to 127 bytes; the field keeps to 64, codes included. */
    const val MAX_LEN = 64

    /** What ^0..^9 look like (the same table as UIB_PMNameHex in touch_uib_player.qc). */
    val PRESETS = listOf(
        "#000000", "#ff0000", "#33ff00", "#ffff00", "#3366ff",
        "#33ffff", "#ff33ff", "#ffffff", "#999999", "#808080",
    )

    /** "^x" + 3 hex digits to "#rrggbb" (each digit d is d * 17). */
    fun xonHex(code: String): String {
        val d = code.removePrefix("^x")
        return "#" + d.map { String.format("%02x", Character.digit(it, 16) * 17) }.joinToString("")
    }

    /** "#rrggbb" to Xonotic's "^xRGB" (4 bits a channel, the nearest of the 16 levels). */
    fun xonCode(hex: String): String {
        val h = hex.removePrefix("#")
        require(h.length == 6) { "need #rrggbb" }
        val sb = StringBuilder("^x")
        for (i in 0 until 3) sb.append(Integer.toHexString((h.substring(i * 2, i * 2 + 2).toInt(16) / 17.0).roundToInt().coerceIn(0, 15)))
        return sb.toString()
    }

    /** The colour a code gives, "#rrggbb"; null for anything that is not ^N or ^xRGB. */
    fun codeHex(code: String): String? = when {
        code.length == 2 && code[0] == '^' && code[1] in '0'..'9' -> PRESETS[code[1] - '0']
        code.length == 5 && code.startsWith("^x") && code.substring(2).all { Character.digit(it, 16) >= 0 } -> xonHex(code.lowercase())
        else -> null
    }

    /** One run of a name: a colour code (`kind` CODE, `hex` the colour it sets), letters (TEXT) or one game-font glyph (GLYPH). */
    enum class Kind { CODE, TEXT, GLYPH }
    class Seg(val start: Int, val end: Int, val kind: Kind, val hex: String)

    /** Colour of text before any code: the default text colour (^7). */
    const val DEFAULT_HEX = "#ffffff"

    /** Splits a name as typed into runs, each with the colour in force. `^^` is text (a caret). */
    fun segments(name: String): List<Seg> {
        val out = ArrayList<Seg>()
        var cur = DEFAULT_HEX
        var i = 0
        var runStart = -1
        fun flush(upTo: Int) { if (runStart >= 0 && upTo > runStart) out.add(Seg(runStart, upTo, Kind.TEXT, cur)); runStart = -1 }
        while (i < name.length) {
            val c = name[i]
            if (c == '^' && i + 1 < name.length) {
                val n = name[i + 1]
                if (n == '^') { if (runStart < 0) runStart = i; i += 2; continue }
                val len = when {
                    n in '0'..'9' -> 2
                    (n == 'x' || n == 'X') && i + 4 < name.length + 0 && name.substring(i + 2, i + 5).all { Character.digit(it, 16) >= 0 } -> 5
                    else -> 0
                }
                if (len > 0) {
                    flush(i)
                    val code = name.substring(i, i + len)
                    cur = codeHex(code.take(2) + code.drop(2).lowercase()) ?: cur
                    out.add(Seg(i, i + len, Kind.CODE, cur))
                    i += len
                    continue
                }
            }
            if (c in ''..'') {
                flush(i)
                out.add(Seg(i, i + 1, Kind.GLYPH, cur))
                i++
                continue
            }
            if (runStart < 0) runStart = i
            i++
        }
        flush(name.length)
        return out
    }

    /** The name as the game would print it, codes and private glyphs removed (what other players' lists show). */
    fun plain(name: String): String = segments(name).filter { it.kind == Kind.TEXT }.joinToString("") { name.substring(it.start, it.end).replace("^^", "^") }

    /**
     * What a key tap does to the field: [key] goes in place of the selection [selStart, selEnd) of
     * [text]. Returns the new text and the caret after the key, or null when the result would pass
     * [max] (the same limit the field's own filter keeps).
     */
    fun insert(text: String, selStart: Int, selEnd: Int, key: String, max: Int = MAX_LEN): Pair<String, Int>? {
        val a = (if (selStart < 0) text.length else selStart).coerceIn(0, text.length)
        val b = (if (selEnd < 0) a else selEnd).coerceIn(a, text.length)
        val next = text.substring(0, a) + key + text.substring(b)
        return if (next.length > max) null else next to a + key.length
    }

    /** True when [key] is a character a plain-text field would eat (the colour codes need no special care: they are text). */
    fun isGlyph(key: String): Boolean = key.length == 1 && key[0] in ''..''

    /**
     * How the game draws a private glyph U+E0xx: the engine maps it to the Quake charset (the same
     * table as UIB_CleanText in touch_uib_json.c): the low seven bits are the letter, 0x80 and up are
     * the "alternate" set, drawn in gold. null for the few control glyphs with no letter.
     */
    class GlyphLook(val text: String, val gold: Boolean)
    fun glyphLook(c: Char): GlyphLook? {
        val v = c.code - 0xE000
        if (v < 0 || v > 0xFF) return null
        val low = v and 0x7f
        val gold = v >= 0x80
        return when {
            low in 0x20..0x7e -> GlyphLook(low.toChar().toString(), gold)
            low == 0x10 -> GlyphLook("[", gold)
            low == 0x11 -> GlyphLook("]", gold)
            low == 0x1d || low == 0x1e || low == 0x1f -> GlyphLook("-", gold)
            else -> null
        }
    }

    /** Dark colours need a light backing, or ^0 black would vanish on the field. */
    fun needsBacking(hex: String): Boolean {
        val h = hex.removePrefix("#")
        val r = h.substring(0, 2).toInt(16); val g = h.substring(2, 4).toInt(16); val b = h.substring(4, 6).toInt(16)
        return (0.299 * r + 0.587 * g + 0.114 * b) < 70
    }
}
