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
        val old = mapOf("featured" to true, "image" to "pk3:maps/boil", "subtitle" to "Level 3 of 31 · Mayhem: Warfare")
        assertEquals(listOf("Level 3 of 31", "Mayhem: Warfare"), CardTags.of(old))
        // a card that is not on Home's row, or has one phrase, stays as it was
        assertTrue(CardTags.of(mapOf("subtitle" to "a · b")).isEmpty())
        assertFalse(CardTags.tagged(mapOf("featured" to true, "subtitle" to "A local game against bots")))
        assertEquals(6, CardTags.of(mapOf("tags" to (1..9).map { "t$it" })).size)
    }

    @Test fun aTableCardKeepsAtMostTwelveLinesOfFourCells() {
        val rows = (1..15).map { listOf("Server $it", "stormkeep", "$it/16", "x", "y") }
        val p = mapOf("display" to "table", "rows" to rows, "subtitle" to "Server 1 · stormkeep · 1/16")
        assertTrue(CardTags.tagged(p))
        assertEquals(12, CardTags.rows(p).size)
        assertEquals(listOf("Server 1", "stormkeep", "1/16", "x"), CardTags.rows(p)[0])
        assertTrue("a table card's lines are its rows, not tags", CardTags.of(p).isEmpty())
        assertTrue(CardTags.tagged(mapOf("display" to "table", "rows" to emptyList<Any>())))
    }

    @Test fun anOlderMultiplayerCardIsALinePerServer() {
        val p = mapOf("featured" to true, "live" to true, "image" to "pk3:maps/vorix", "subtitle" to "Jeff & Julius · Server · circle_jerk · 12/32\nQuake 1 · qbj · 3/12")
        assertTrue(CardTags.tagged(p))
        assertEquals(listOf("Jeff & Julius · Server", "circle_jerk", "12/32"), CardTags.rows(p)[0])
        assertEquals(listOf("Quake 1", "qbj", "3/12"), CardTags.rows(p)[1])
        assertTrue(CardTags.of(p).isEmpty())
    }

    @Test fun cellTextStepsDownOnANarrowCard() {
        assertEquals(13f, CardTags.sizeFor(0, 2f), 0f)
        assertEquals(13f, CardTags.sizeFor(1200, 2f), 0f)   // 600 dp
        assertEquals(11.5f, CardTags.sizeFor(560, 2f), 0f)  // 280 dp
        assertEquals(10f, CardTags.sizeFor(300, 2f), 0f)    // a third of a phone
    }

    @Test fun aTableCardShowsAsManyWholeLinesAsItsHeightHolds() {
        // never a clipped half line, never more than there are or than twelve
        assertEquals(0, CardTags.fitRows(45, 46, 12))
        assertEquals(1, CardTags.fitRows(46, 46, 12))
        assertEquals(3, CardTags.fitRows(183, 46, 12))
        assertEquals(4, CardTags.fitRows(184, 46, 12))
        assertEquals(12, CardTags.fitRows(5000, 46, 40))
        assertEquals(5, CardTags.fitRows(5000, 46, 5))
        assertEquals(0, CardTags.fitRows(-10, 46, 5))
    }

    @Test fun serverLinesAreBiggerThanTagsAndGrowWithTheCard() {
        for (w in listOf(200, 300, 400, 600)) {
            val d = 2f
            assertTrue("w=$w", CardTags.tableSizeFor((w * d).toInt(), d) > CardTags.sizeFor((w * d).toInt(), d))
        }
        assertEquals(52f, CardTags.tableRowDp(600f), 0f)
        assertEquals(32f, CardTags.tableRowDp(180f), 0f)
        assertTrue(CardTags.NAME_WEIGHT > CardTags.MAP_WEIGHT)
    }
}
