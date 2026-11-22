package io.github.dixonsolutions.godnotic.fe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's cards: the details as one line of tags, one line per server on the Multiplayer card. */
class CardTagsTest {
    @Test fun tagsAreTheDetailsAndFallBackToTheSubtitleOfAFeaturedCard() {
        val withTags = mapOf("featured" to true, "tags" to listOf("Stormkeep", "Deathmatch", "4 bots"), "subtitle" to "x · y")
        assertEquals(listOf("Stormkeep", "Deathmatch", "4 bots"), CardTags.of(withTags))
        // an older payload: only the subtitle, split at the separator
        val old = mapOf("featured" to true, "subtitle" to "Level 3 of 31 · Mayhem: Warfare")
        assertEquals(listOf("Level 3 of 31", "Mayhem: Warfare"), CardTags.of(old))
        // a card that is not on Home's row, or has one phrase, stays as it was
        assertTrue(CardTags.of(mapOf("subtitle" to "a · b")).isEmpty())
        assertFalse(CardTags.tagged(mapOf("featured" to true, "subtitle" to "A local game against bots")))
        assertEquals(6, CardTags.of(mapOf("tags" to (1..9).map { "t$it" })).size)
    }

    @Test fun aTableCardShowsAtMostSixLinesOfFourCells() {
        val rows = (1..8).map { listOf("Server $it", "stormkeep", "$it/16", "x", "y") }
        val p = mapOf("display" to "table", "rows" to rows, "subtitle" to "Server 1 · stormkeep · 1/16")
        assertTrue(CardTags.tagged(p))
        assertEquals(6, CardTags.rows(p).size)
        assertEquals(listOf("Server 1", "stormkeep", "1/16", "x"), CardTags.rows(p)[0])
        assertTrue("a table card's lines are its rows, not tags", CardTags.of(p).isEmpty())
        assertTrue(CardTags.tagged(mapOf("display" to "table", "rows" to emptyList<Any>())))
    }

    @Test fun cellTextStepsDownOnANarrowCard() {
        assertEquals(13f, CardTags.sizeFor(0, 2f), 0f)
        assertEquals(13f, CardTags.sizeFor(1200, 2f), 0f)   // 600 dp
        assertEquals(11.5f, CardTags.sizeFor(560, 2f), 0f)  // 280 dp
        assertEquals(10f, CardTags.sizeFor(300, 2f), 0f)    // a third of a phone
    }
}
