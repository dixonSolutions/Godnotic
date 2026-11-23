package io.github.dixonsolutions.godnotic.fe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Name field's text (NameColour): colour codes, game-font glyphs, what a keypad key does at the caret. */
class NameColourTest {
    private fun kinds(name: String) = NameColour.segments(name).map { "${it.kind}:${name.substring(it.start, it.end)}:${it.hex}" }

    @Test fun codesAndColours() {
        assertEquals(listOf("TEXT:Anna:#ffffff"), kinds("Anna"))
        assertEquals(listOf("CODE:^1:#ff0000", "TEXT:An:#ff0000", "CODE:^x0f0:#00ff00", "TEXT:na:#00ff00"), kinds("^1An^x0f0na"))
        assertEquals(listOf("CODE:^xF80:#ff8800", "TEXT:x:#ff8800"), kinds("^xF80x"))
    }

    @Test fun caretAndBrokenCodesAreText() {
        assertEquals(listOf("TEXT:a^^b:#ffffff"), kinds("a^^b"))
        assertEquals(listOf("TEXT:a^b:#ffffff"), kinds("a^b"))
        assertEquals(listOf("TEXT:^x12:#ffffff"), kinds("^x12"))   // too short for ^xRGB
        assertEquals(listOf("TEXT:end^:#ffffff"), kinds("end^"))
    }

    @Test fun plainNameDropsCodesAndGlyphs() {
        assertEquals("Anna", NameColour.plain("^1An^x0f0na"))
        assertEquals("a^b", NameColour.plain("a^^b"))
        assertEquals("ab", NameColour.plain("a\uE0E1b"))
        assertEquals("", NameColour.plain("^1^2"))
    }

    @Test fun codeColours() {
        assertEquals("#ff0000", NameColour.codeHex("^1")); assertEquals("#ff8800", NameColour.codeHex("^xf80"))
        assertNull(NameColour.codeHex("^a")); assertNull(NameColour.codeHex("^x1")); assertEquals(10, NameColour.PRESETS.size)
        assertEquals("^xf80", NameColour.xonCode("#ff8800")); assertEquals("^x888", NameColour.xonCode("#898989"))
    }

    @Test fun glyphsAreSegmentsWithTheColourInForce() {
        assertEquals(listOf("CODE:^2:#33ff00", "GLYPH:\uE0E1:#33ff00", "TEXT:z:#33ff00"), kinds("^2\uE0E1z"))
    }

    @Test fun keyInsertsAtTheCaret() {
        assertEquals("Ra^1ven" to 5, NameColour.insert("Raven", 2, 2, "^1"))
        assertEquals("^1" to 2, NameColour.insert("", 0, 0, "^1"))
        assertEquals("R\u2605n" to 3, NameColour.insert("Raven", 1, 4, "\u2605"))   // replaces the selection
        assertEquals("Raven^2" to 7, NameColour.insert("Raven", -1, -1, "^2"))      // no caret: at the end
        assertEquals("\uD83D\uDE03Raven" to 2, NameColour.insert("Raven", 0, 0, "\uD83D\uDE03"))   // a face is two UTF-16 units
    }

    @Test fun keyRespectsTheLimit() {
        val full = "x".repeat(NameColour.MAX_LEN)
        assertNull(NameColour.insert(full, 3, 3, "^1"))
        assertNotNull(NameColour.insert(full, 3, 5, "^1"))   // replacing two letters with two characters fits
        assertEquals(NameColour.MAX_LEN, NameColour.insert(full, 3, 5, "^1")!!.first.length)
    }

    @Test fun gameFontGlyphsDrawLikeTheEngine() {
        // U+E0E1 is the alternate set's 'a': a gold 'a', as the game draws it (touch_uib_json.c UIB_CleanText)
        val a = NameColour.glyphLook('\uE0E1')!!
        assertEquals("a", a.text); assertTrue(a.gold)
        val d = NameColour.glyphLook('\uE0B5')!!
        assertEquals("5", d.text); assertTrue(d.gold)
        val low = NameColour.glyphLook('\uE041')!!
        assertEquals("A", low.text); assertFalse(low.gold)
        assertEquals("[", NameColour.glyphLook('\uE010')!!.text)
        assertNull(NameColour.glyphLook('\uE001')); assertNull(NameColour.glyphLook('x'))
        assertTrue(NameColour.isGlyph("\uE0E1")); assertFalse(NameColour.isGlyph("^1"))
    }

    @Test fun darkColoursGetABacking() {
        assertTrue(NameColour.needsBacking("#000000")); assertTrue(NameColour.needsBacking("#0000ff"))
        assertFalse(NameColour.needsBacking("#ffffff")); assertFalse(NameColour.needsBacking("#999999"))
    }
}
