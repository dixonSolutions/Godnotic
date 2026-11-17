package io.github.dixonsolutions.godnotic.fe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server browser across a refresh: rows keyed by address, kept on screen
 * while the engine re-queries, dimmed for a grace period when a server stops
 * answering, ordered with ties broken by address, and one frame's replies
 * landing as one update. Plain JVM tests: no Android.
 */
class ServerListStateTest {

    private var now = 0L
    private val state = ServerListState(clock = { now })

    private fun row(key: String, ping: Int = 50, players: Int = 0, name: String = key): Map<String, Any?> =
        mapOf("key" to key, "cells" to mapOf("address" to key, "name" to name, "ping" to ping.toDouble(), "players" to players.toDouble()))

    private fun keys(s: ServerListState = state): List<String?> = (0 until s.size).map { s.keyAt(it) }

    @Test
    fun cachedRowsArePublishedWhenTheScreenOpens() {
        state.accept(0, 3, listOf(row("a"), row("b"), row("c")))
        val window = ListWindow("mp/servers")
        state.publish { offset, total, rows -> window.accept(mapOf("offset" to offset, "total" to total, "rows" to rows)) }
        assertEquals(3, window.total)
        assertEquals(listOf("a", "b", "c"), (0 until window.total).map { window.row(it)!!.str("key") })
    }

    @Test
    fun firstBatchImmediatelyPopulatesAnOpenScreen() {
        val window = ListWindow("mp/servers")
        state.publish { offset, total, rows -> window.accept(mapOf("offset" to offset, "total" to total, "rows" to rows)) }
        assertEquals(-1, window.total)
        state.accept(0, 2, listOf(row("a"), row("b")))
        state.publish { offset, total, rows -> window.accept(mapOf("offset" to offset, "total" to total, "rows" to rows)) }
        assertEquals(2, window.loaded)
        assertEquals("a", window.row(0)!!.str("key"))
    }

    @Test
    fun emptyMessageWaitsForACompletedEmptyQuery() {
        assertFalse(ServerListState.showEmpty("ready", -1))
        assertFalse(ServerListState.showEmpty("loading", 0))
        assertFalse(ServerListState.showEmpty("ready", 2))
        assertTrue(ServerListState.showEmpty("ready", 0))
    }

    @Test
    fun aRefreshNeverClearsTheList() {
        state.accept(0, 3, listOf(row("a:1"), row("b:2"), row("c:3")))
        assertEquals(listOf("a:1", "b:2", "c:3"), keys())

        // the engine reports a new revision before any window of it arrives
        state.begin(3)
        assertEquals(3, state.size)
        assertEquals(listOf("a:1", "b:2", "c:3"), keys())
        state.accept(0, 3, listOf(row("a:1"), row("b:2"), row("c:3")))

        // and even a listing that reports nothing keeps them for the grace period
        state.begin(0)
        state.settle()
        assertEquals(3, state.size)
        assertTrue("waiting for its replacement", state.row(0)!!.bool("stale"))
    }

    @Test
    fun rowsUpdateInPlaceByAddress() {
        state.accept(0, 2, listOf(row("a:1", ping = 40, players = 2), row("b:2", ping = 90)))
        state.begin(2)
        state.accept(0, 2, listOf(row("a:1", ping = 35, players = 5), row("b:2", ping = 95)))

        assertEquals(listOf("a:1", "b:2"), keys())
        val cells = state.row(0)!!["cells"].obj()
        assertEquals(35, cells.int("ping"))
        assertEquals(5, cells.int("players"))
        assertFalse("answered this refresh", state.row(0)!!.bool("stale"))
    }

    @Test
    fun aSilentServerIsDimmedThenDroppedAfterTwoRefreshes() {
        state.accept(0, 2, listOf(row("a:1"), row("b:2")))

        state.begin(1)
        state.accept(0, 1, listOf(row("a:1")))
        state.settle()
        assertEquals("still on screen, dimmed", 2, state.size)
        assertTrue(state.row(1)!!.bool("stale"))
        assertFalse(state.row(0)!!.bool("stale"))

        state.begin(1)
        state.accept(0, 1, listOf(row("a:1")))
        state.settle()
        assertEquals(1, state.size)
        assertEquals(listOf<String?>("a:1"), keys())
    }

    @Test
    fun theGracePeriodAlsoEndsAfterTenSeconds() {
        state.accept(0, 2, listOf(row("a:1"), row("b:2")))
        state.begin(1)
        state.accept(0, 1, listOf(row("a:1")))
        state.settle()
        assertEquals(2, state.size)

        now = 10_001
        state.settle()
        assertEquals(1, state.size)
    }

    @Test
    fun tiesAreBrokenByAddressSoEqualPingsNeverSwap() {
        state.choose("ping", false)
        state.accept(0, 3, listOf(row("c:3", 50), row("a:1", 50), row("b:2", 50)))
        assertEquals(listOf("a:1", "b:2", "c:3"), keys())
    }

    @Test
    fun aSortOnlyReOrdersWithinTheWindowItCameIn() {
        state.choose("ping", false)
        state.accept(0, 4, listOf(row("d:4", 10), row("c:3", 20), row("b:2", 30), row("a:1", 40)))
        assertEquals(listOf("d:4", "c:3", "b:2", "a:1"), keys())

        // a window that covers only the last two rows re-sorts those two, not the list
        state.begin(4)
        state.accept(2, 4, listOf(row("b:2", 30), row("a:1", 40)))
        assertEquals(listOf("d:4", "c:3", "b:2", "a:1"), keys())
    }

    @Test
    fun descendingKeepsTiesInAddressOrder() {
        state.choose("ping", true)
        state.accept(0, 3, listOf(row("b:2", 50), row("a:1", 50), row("c:3", 20)))
        assertEquals(listOf("a:1", "b:2", "c:3"), keys())
    }

    @Test
    fun nothingReOrdersUnderAFinger() {
        state.choose("ping", false)
        state.hold = true
        state.accept(0, 3, listOf(row("c:3", 50), row("a:1", 50), row("b:2", 50)))
        assertEquals(listOf("c:3", "a:1", "b:2"), keys())

        state.hold = false
        state.begin(3)
        state.accept(0, 3, listOf(row("c:3", 50), row("a:1", 50), row("b:2", 50)))
        assertEquals(listOf("a:1", "b:2", "c:3"), keys())
    }

    @Test
    fun onlyRowsSeenAfterTheFirstListingAreFresh() {
        state.accept(0, 2, listOf(row("a:1"), row("b:2")))
        assertFalse("a first paint is not an arrival", state.row(0)!!.bool("fresh"))

        state.begin(3)
        state.accept(0, 3, listOf(row("a:1"), row("b:2"), row("c:3")))
        assertFalse(state.row(0)!!.bool("fresh"))
        assertTrue(state.row(2)!!.bool("fresh"))
    }

    @Test
    fun aWindowBecomesOneUpdatePerLoadedRun() {
        state.accept(0, 2, listOf(row("a:1"), row("b:2")))
        state.begin(6)

        val runs = state.runs()
        assertEquals(1, runs.size)
        assertEquals(0, runs[0].first)
        assertEquals(2, runs[0].second.size)
        assertEquals("a:1", runs[0].second[0].str("key"))
    }

    @Test
    fun rowsWithoutAKeyFallBackToTheirAddress() {
        val r = mapOf("cells" to mapOf("address" to "10.0.0.1:26000", "ping" to 12.0))
        state.accept(0, 1, listOf(r))
        assertEquals("10.0.0.1:26000", state.keyAt(0))
        assertEquals(1, state.size)
    }

    @Test
    fun theChosenSortIsWhatTheListAsksFor() {
        assertTrue(state.merge(mapOf("title" to "Servers"))["title"] == "Servers")
        assertNull(state.merge(mapOf("title" to "Servers"))["sort"])

        state.choose("players", true)
        val props = state.merge(mapOf("sort" to "ping", "desc" to false))
        assertEquals("players", props["sort"])
        assertEquals(true, props["desc"])
    }

    @Test
    fun anEmptyListingSettlesToNothingAfterGrace() {
        state.accept(0, 1, listOf(row("a:1")))
        state.begin(0)
        state.settle()
        assertEquals(1, state.size)

        now = 11_000
        state.settle()
        assertEquals(0, state.size)
        assertNull(state.row(0))
    }
}
