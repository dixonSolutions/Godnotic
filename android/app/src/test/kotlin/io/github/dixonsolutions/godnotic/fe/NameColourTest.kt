package io.github.dixonsolutions.godnotic.fe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The name colour picker's maths (NameColour): HSV to ^xRGB, presets, palette indexes, name length. */
class NameColourTest {
    @Test fun hsvPrimaries() {
        assertEquals("#ff0000", NameColour.hsvToHex(0.0, 1.0, 1.0))
        assertEquals("#00ff00", NameColour.hsvToHex(120.0, 1.0, 1.0))
        assertEquals("#0000ff", NameColour.hsvToHex(240.0, 1.0, 1.0))
        assertEquals("#ffffff", NameColour.hsvToHex(77.0, 0.0, 1.0))
        assertEquals("#000000", NameColour.hsvToHex(200.0, 1.0, 0.0))
        assertEquals("#ff0000", NameColour.hsvToHex(360.0, 1.0, 1.0))
    }

    @Test fun hsvToXonoticCode() {
        assertEquals("^xf00", NameColour.xonCode(NameColour.hsvToHex(0.0, 1.0, 1.0)))
        assertEquals("^x0f0", NameColour.xonCode(NameColour.hsvToHex(120.0, 1.0, 1.0)))
        assertEquals("^xfff", NameColour.xonCode("#ffffff"))
        assertEquals("^x000", NameColour.xonCode("#000000"))
        assertEquals("^x888", NameColour.xonCode("#898989"))
        assertEquals("^xf80", NameColour.xonCode("#ff8800"))
        assertEquals("#ff8800", NameColour.xonHex("^xf80"))
    }

    @Test fun presetsAndShirtPalette() {
        assertEquals("^0", NameColour.presetCode(0)); assertEquals("^9", NameColour.presetCode(9))
        assertEquals("^9", NameColour.presetCode(40)); assertEquals(10, NameColour.PRESETS.size)
        assertEquals(16, NameColour.SHIRT.size)
        for (i in 0..15) if (i != 7) assertEquals(i, NameColour.shirtIndex(NameColour.SHIRT[i]))
        assertEquals(0, NameColour.shirtIndex("#ffffff"))   // 7 repeats white; 0 wins
        assertEquals(1, NameColour.shirtIndex("#FF0000"))
    }

    @Test fun parseAndSerialise() {
        assertEquals("Anna", NameColour.plain("^1An^x0f0na"))
        assertEquals("a^b", NameColour.plain("a^^b"))
        val chars = NameColour.parse("^1ab^x0f0c")
        assertEquals(listOf("^1", "^1", "^x0f0"), chars.map { it.code })
        assertEquals("^1ab^x0f0c", NameColour.serialise(chars))
    }

    @Test fun paintWholeAndPart() {
        assertEquals("^x0f0Anna", NameColour.paint("Anna", 0, 4, "^x0f0"))
        assertEquals("An^1na", NameColour.paint("Anna", 2, 4, "^1"))
        assertEquals("A^2nn^7a", NameColour.paint("Anna", 1, 3, "^2"))
        // painting over an older colour replaces it
        assertEquals("^2Anna", NameColour.paint("^1Anna", 0, 4, "^2"))
        assertEquals("^1A^2nn^1a", NameColour.paint("^1Anna", 1, 3, "^2"))
        assertEquals("Anna", NameColour.paint("Anna", 2, 2, "^1").let { NameColour.plain(it!!) })
    }

    @Test fun caretsSurvive() {
        assertEquals("^1a^^b", NameColour.paint("a^^b", 0, 3, "^1"))
    }

    @Test fun lengthCountsCodes() {
        val name = "x".repeat(NameColour.MAX_LEN - 5)
        assertEquals("^xf00$name", NameColour.paint(name, 0, name.length, "^xf00"))     // exactly 64
        assertNull(NameColour.paint(name + "y", 0, name.length + 1, "^xf00"))           // 65: refused
        assertEquals(NameColour.MAX_LEN, NameColour.paint(name, 0, name.length, "^xf00")!!.length)
        // a preset costs 2
        assertNull(NameColour.paint("y".repeat(63), 0, 63, "^xf00", 64))
    }
}
