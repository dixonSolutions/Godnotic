package io.github.dixonsolutions.godnotic.fe

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The maths behind the player-name colour picker (ColourPicker): HSV to Xonotic's `^xRGB`
 * code, the `^0`..`^9` presets, and colouring part of a name. Plain Kotlin, no Android, so
 * the JVM tests cover it. The QC side (touch_uib_player.qc) re-checks every name it gets.
 */
object NameColour {
    /** The engine takes names of up to 127 bytes; the picker keeps to 64, codes included. */
    const val MAX_LEN = 64

    /** What ^0..^9 look like (the same table as UIB_PMNameHex in touch_uib_player.qc). */
    val PRESETS = listOf(
        "#000000", "#ff0000", "#33ff00", "#ffff00", "#3366ff",
        "#33ffff", "#ff33ff", "#ffffff", "#999999", "#808080",
    )

    /** h 0..360, s and v 0..1 to "#rrggbb". */
    fun hsvToHex(h: Double, s: Double, v: Double): String {
        val hh = ((h % 360.0) + 360.0) % 360.0 / 60.0
        val c = v * s
        val x = c * (1 - kotlin.math.abs(hh % 2 - 1))
        val m = v - c
        val (r, g, b) = when (floor(hh).toInt()) {
            0 -> Triple(c, x, 0.0); 1 -> Triple(x, c, 0.0); 2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c); 4 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
        }
        fun ch(f: Double) = ((f + m) * 255).roundToInt().coerceIn(0, 255)
        return "#%02x%02x%02x".format(ch(r), ch(g), ch(b))
    }

    /** "#rrggbb" to Xonotic's "^xRGB" (4 bits a channel, the nearest of the 16 levels). */
    fun xonCode(hex: String): String {
        val h = hex.removePrefix("#")
        require(h.length == 6) { "need #rrggbb" }
        val sb = StringBuilder("^x")
        for (i in 0 until 3) sb.append(Integer.toHexString((h.substring(i * 2, i * 2 + 2).toInt(16) / 17.0).roundToInt().coerceIn(0, 15)))
        return sb.toString()
    }

    /** "^x" + 3 hex digits back to "#rrggbb" (each digit d is d * 17). */
    fun xonHex(code: String): String {
        val d = code.removePrefix("^x")
        return "#" + d.map { String.format("%02x", Character.digit(it, 16) * 17) }.joinToString("")
    }

    fun presetCode(i: Int): String = "^" + i.coerceIn(0, 9)

    /** The shirt/pants palette: index 0..15 (7 repeats white: left out), one hex each. */
    val SHIRT = listOf(
        "#ffffff", "#ff0000", "#33ff00", "#ffff00", "#3366ff", "#00ffff", "#ff3366", "#ffffff",
        "#ff6600", "#9933ff", "#ff00ff", "#00ff99", "#ccff00", "#0099ff", "#ffcc00", "#666666",
    )
    fun shirtIndex(hex: String): Int = SHIRT.indices.firstOrNull { SHIRT[it] == hex.lowercase() && it != 7 } ?: 0

    /** One character of a name and the colour code in force on it (null: none yet). */
    class Ch(val c: Char, val code: String?)

    /** Splits a name with codes into characters and the code in force on each. */
    fun parse(name: String): List<Ch> {
        val out = ArrayList<Ch>()
        var cur: String? = null
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (c == '^' && i + 1 < name.length) {
                val n = name[i + 1]
                if (n == '^') { out.add(Ch('^', cur)); i += 2; continue }
                if (n in '0'..'9') { cur = "^$n"; i += 2; continue }
                if ((n == 'x' || n == 'X') && i + 4 < name.length + 0 && name.substring(i + 2, minOf(name.length, i + 5)).let { it.length == 3 && it.all { ch -> Character.digit(ch, 16) >= 0 } }) {
                    cur = "^x" + name.substring(i + 2, i + 5).lowercase(); i += 5; continue
                }
            }
            out.add(Ch(c, cur)); i++
        }
        return out
    }

    fun plain(name: String): String = parse(name).joinToString("") { it.c.toString() }

    /** The shortest name with codes that gives each character its colour. */
    fun serialise(chars: List<Ch>): String {
        val sb = StringBuilder()
        var cur: String? = null
        for (ch in chars) {
            if (ch.code != cur) { sb.append(ch.code ?: "^7"); cur = ch.code }
            if (ch.c == '^') sb.append("^^") else sb.append(ch.c)
        }
        return sb.toString()
    }

    /** Length of the name the engine will hold: codes and doubled carets count. */
    fun length(name: String): Int = name.length

    /**
     * Gives characters [from, to) of the plain name the code; null when the result
     * would pass [max]. An empty range or a code that is not ^N or ^xRGB changes nothing.
     */
    fun paint(name: String, from: Int, to: Int, code: String, max: Int = MAX_LEN): String? {
        val chars = parse(name)
        val a = from.coerceIn(0, chars.size); val b = to.coerceIn(a, chars.size)
        val next = chars.mapIndexed { i, ch -> if (i in a until b) Ch(ch.c, code) else ch }
        val s = serialise(next)
        return if (s.length > max) null else s
    }

    /** Keeps the name inside [max]: paints, then returns the result or the old name. */
    fun withinLimit(name: String, max: Int = MAX_LEN): Boolean = name.length <= max
}
