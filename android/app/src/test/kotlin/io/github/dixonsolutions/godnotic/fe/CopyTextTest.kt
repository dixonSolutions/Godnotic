package io.github.dixonsolutions.godnotic.fe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a long press copies (CopyText): the formats are plain Kotlin, so the
 * text on the clipboard is checked here instead of on a device. The
 * ClipboardManager call itself is in Kit.copyTo.
 */
class CopyTextTest {

    private fun cells(vararg pairs: Pair<String, Any?>) = mapOf(*pairs)

    @Test
    fun aServerCopiesItsNameAddressAndFacts() {
        val c = cells(
            "name" to "Duel Pit", "address" to "203.0.113.7:26000", "map" to "stormkeep",
            "mode" to "duel", "players" to 3.0, "max" to 16.0, "ping" to 42.0, "version" to "0.8.6"
        )
        assertEquals("Duel Pit (203.0.113.7:26000) · stormkeep · duel · 3/16 · 42 ms · 0.8.6", CopyText.server(c))
    }

    @Test
    fun aRowWithoutAnAddressFallsBackToItsKey() {
        val c = cells("name" to "LAN game")
        assertEquals("LAN game (10.0.0.2:26000)", CopyText.server(c, "10.0.0.2:26000"))
    }

    @Test
    fun aRowWithoutANameCopiesTheAddressAlone() {
        val c = cells("address" to "example:26000", "ping" to 12.0)
        assertEquals("example:26000 · 12 ms", CopyText.server(c))
    }

    @Test
    fun factsThatAreZeroAreLeftOut() {
        val c = cells("name" to "Empty", "address" to "a:1", "players" to 0.0, "max" to 0.0, "ping" to 0.0)
        assertEquals("Empty (a:1)", CopyText.server(c))
    }

    @Test
    fun aSettingCopiesAsLabelAndValue() {
        assertEquals("Volume: 80%", CopyText.line("Volume", "80%"))
        assertEquals("Volume", CopyText.line("Volume", ""))
        assertEquals("80%", CopyText.line("", "80%"))
    }

    @Test
    fun clipTrimsAndNeverCarriesLineBreaksAtTheEdges() {
        assertEquals("1000", CopyText.clip("  1000  "))
        assertEquals("", CopyText.clip("   "))
        assertTrue(CopyText.clip("\nnote\n").startsWith("note"))
    }
}
