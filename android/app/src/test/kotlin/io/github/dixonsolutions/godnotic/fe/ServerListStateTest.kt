package io.github.dixonsolutions.godnotic.fe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private fun row(key: String, ping: Int = 50, players: Int = 0, name: String = key, net: String = "internet", max: Int = 16, bots: Int = 0): Map<String, Any?> =
        mapOf("key" to key, "cells" to mapOf("address" to key, "name" to name, "ping" to ping.toDouble(), "players" to players.toDouble(),
            "max" to max.toDouble(), "bots" to bots.toDouble(), "net" to net))

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

    /**
     * The exact message sequence a REFRESH produces, as the engine sends it
     * (touch_uib_src.c, touch_srvlist.c, loader/crates/android/src/driver.rs):
     *
     *   source total 0   the engine reset its cache (this is its "clear")
     *   rows [...]       the saved rows are back, in the window asked for
     *   source ...       one per answer: the revision moved (4 a second)
     *   rows [...]       the window the renderer asked for again
     *   state ready      `_slist_refreshing` went to 0: the query is over
     *
     * ViewRenderer turns the source notices into begin() and the windows into
     * accept(); it settles when the list's state leaves `loading`. The list
     * must survive the clear and every notice without a row moving off screen.
     */
    @Test
    fun aRefreshNeverDropsTheListToZeroOrLosesARow() {
        state.accept(0, 3, listOf(row("a:1"), row("b:2"), row("c:3")))
        val sizes = ArrayList<Int>()
        fun painted(tag: String) {
            sizes.add(state.size)
            val keys = keys().filterNotNull()
            assertEquals("$tag: a row went missing", setOf("a:1", "b:2", "c:3"), keys.toSet())
            assertEquals("$tag: every row is still drawn", 3, keys.size)
            for (i in 0 until state.size) assertNotNull("$tag: a hole appeared at $i", state.row(i))
        }
        painted("listed")

        // REFRESH: the engine wipes its cache and broadcasts the empty source
        state.begin(0)
        painted("the refresh's clear")
        state.begin(0)
        painted("a second clear notice")
        // the saved rows come back: the window for them (b has not answered)
        state.accept(0, 2, listOf(row("a:1", ping = 31), row("c:3", ping = 44)))
        painted("the first window")
        // every answer moves the revision: another notice, then the window again
        state.begin(2)
        painted("a source notice while refreshing")
        state.begin(2)
        painted("another source notice while refreshing")
        state.accept(0, 2, listOf(row("a:1", ping = 18), row("c:3", ping = 42)))
        painted("a later window")
        // the query ended: the list's state left `loading`
        state.settle()
        painted("the query finished")

        assertTrue("the list never dropped to zero", sizes.all { it == 3 })
        assertTrue("b did not answer: dimmed, not removed", state.row(keys().indexOf("b:2"))!!.bool("stale"))
        assertFalse("a answered", state.row(keys().indexOf("a:1"))!!.bool("stale"))
        assertFalse("c answered", state.row(keys().indexOf("c:3"))!!.bool("stale"))

        // a second refresh, b still silent: through its grace period, then gone
        state.begin(0)
        painted("the second refresh's clear")
        state.begin(2)
        state.accept(0, 2, listOf(row("a:1"), row("c:3")))
        state.settle()
        assertEquals("b went after two silent queries", listOf("a:1", "c:3"), keys())
        assertTrue("never zero", sizes.all { it > 0 })
    }

    @Test
    fun aChipReFiltersInTheSameFrameAndFlipsOncePerTap() {
        state.accept(0, 5, listOf(
            row("lan:1", net = "lan"),
            row("vpn:2", net = "vpn"),
            row("net:3", ping = 40, players = 2),
            row("net:4", ping = 240, players = 2),
            row("net:5", ping = 40, players = 8, max = 8)))
        assertEquals(5, state.size)

        assertTrue("LAN was off", state.toggle("lan"))
        assertEquals(listOf("lan:1", "vpn:2"), keys())
        assertFalse("one tap is one flip", state.toggle("lan"))
        assertEquals(5, state.size)

        assertFalse("Laggy starts on", state.toggle("laggy"))
        assertEquals(listOf("lan:1", "vpn:2", "net:3", "net:5"), keys())
        assertFalse("Empty starts on", state.toggle("empty"))
        assertEquals(listOf("net:3", "net:5"), keys())
        assertFalse("Full starts on", state.toggle("full"))
        assertEquals(listOf("net:3"), keys())
        assertEquals(1, state.size)
        assertTrue(state.chosen("lan") && state.chosen("empty") && state.chosen("full") && state.chosen("laggy"))
    }

    @Test
    fun botsDoNotCountAsPlayersAndABotOnlyServerIsEmpty() {
        state.accept(0, 2, listOf(row("a:1", players = 6, bots = 2), row("b:2", players = 2, bots = 2)))
        state.toggle("empty")
        assertEquals(listOf<String?>("a:1"), keys())
    }

    @Test
    fun aChosenRowSurvivesEveryRefreshAndItsOwnAddressIsKept() {
        state.accept(0, 2, listOf(row("a:1"), row("b:2")))
        assertTrue(state.select("b:2"))
        assertFalse("the same row again is not a new choice", state.select("b:2"))
        assertEquals("b:2", state.selected)

        val engine = mapOf("selection" to "single", "selected" to emptyList<Any?>(), "sort" to "ping", "desc" to false)
        assertEquals(listOf("b:2"), state.merge(engine)["selected"])

        state.begin(2)
        state.accept(0, 2, listOf(row("a:1", ping = 33), row("b:2", ping = 44)))
        assertEquals(listOf("b:2"), state.merge(engine)["selected"])

        state.toggle("lan")
        state.toggle("lan")
        assertEquals("a filter is not a selection", "b:2", state.selected)
        assertTrue(state.select(""))
        assertEquals(emptyList<Any?>(), state.merge(engine)["selected"])
    }

    @Test
    fun theHeaderCountFollowsTheRowsDrawn() {
        assertEquals("12 servers · 3 players · refreshing…",
            ServerListState.summary("157 servers · 6 players · refreshing…", 12, 3))
        assertEquals("0 servers · 0 players", ServerListState.summary("157 servers · 6 players", 0, 0))
        assertEquals("Looking for servers…", ServerListState.summary("Looking for servers…", 3, 1))
        assertEquals("", ServerListState.summary("", 3, 1))
        assertEquals(5, ServerListState.players(listOf(row("a:1", players = 6, bots = 1), row("b:2", players = 2, bots = 2))))
    }

    /** Publishes into a list window the way ViewRenderer's flushServers does. */
    private fun screen(state: ServerListState, window: ListWindow) {
        state.publish { offset, total, rows ->
            window.accept(mapOf("offset" to offset.toDouble(), "total" to total.toDouble(), "rows" to rows))
        }
    }

    /**
     * A fresh install: the browser opens while the engine's cache is still empty
     * (count 0), so the list has no page to ask for; the cache then fills and the
     * source notice carries the count. The model must tell the list the source has
     * rows even though none have arrived, or the list asks for nothing, ever.
     */
    @Test
    fun aFreshListingStartsItsQueryAndItsRowsReachTheScreen() {
        val state = ServerListState()
        val window = ListWindow("mp/servers", 50)
        screen(state, window)
        assertEquals("nothing has been listed yet", -1, window.total)

        // the list node binds with count 0: the engine cleared its cache for the refresh
        state.begin(0)
        screen(state, window)
        assertEquals(0, window.total)
        assertNull("zero rows: nothing to ask for yet", window.missing(0, 20))

        // the cache refills: 157 rows, no window of them sent yet
        state.begin(157)
        screen(state, window)
        assertEquals(157, window.total)
        assertEquals("the list asks for page 0, so the query runs", 0..49, window.missing(0, 20))

        // the engine answers the window: the rows reach the model and the list
        state.accept(0, 157, (0 until 50).map { row("s$it:26000") })
        screen(state, window)
        assertEquals("s0:26000", window.row(0)!!.str("key"))
        assertEquals(50, window.loaded)
        assertNull("page 0 is here", window.missing(0, 20))
    }

    /**
     * A refresh: the engine broadcasts a source notice per change and expects the
     * visible window to be asked for again. Keeping the cached rows and never
     * asking again left pings and players frozen (and a refresh never happened).
     */
    @Test
    fun aRefreshAsksForTheVisibleWindowAgain() {
        val window = ListWindow("mp/servers", 50)
        window.accept(mapOf("offset" to 0.0, "total" to 157.0, "rows" to (0 until 50).map { row("s$it") }))
        assertNull("page 0 is loaded", window.missing(0, 20))

        window.refreshWindow(0, 20) // what VirtualList.reask does for a source notice
        assertEquals("asked again despite the cached rows", 0..49, window.missing(0, 20))

        window.accept(mapOf("offset" to 0.0, "total" to 157.0, "rows" to listOf(row("s0", ping = 12))))
        assertNull("the answer ends the re-read", window.missing(0, 20))
    }

    /**
     * The chips show the engine's settings (`menu_slist_showempty`, `mp.lan`), and the
     * model's defaults can differ (Laggy starts off in Xonotic, on here): a chip the
     * engine has off must read off, or its first tap flips it to off again and looks
     * dead, and a filter the engine applies must not be "included" locally.
     */
    @Test
    fun aChipFollowsTheEngineUntilThePlayerFlipsIt() {
        state.accept(0, 3, listOf(row("slow:1", ping = 320), row("a:2", ping = 30), row("empty:3", players = 0)))
        assertTrue("the model's guess before it hears the engine", state.include("laggy"))

        state.sync("laggy", false) // the engine hides laggy servers
        state.sync("empty", true)
        assertFalse("the model hears the engine", state.include("laggy"))
        assertEquals("the laggy row is filtered as the engine did", listOf("a:2", "empty:3"), keys())

        assertTrue("one tap turns it on", state.toggle("laggy"))
        assertEquals(3, state.size)
        state.sync("laggy", false) // engine props echoing the value from before the tap
        assertTrue("a stale echo cannot flip it back", state.include("laggy"))
        assertFalse("one tap is one flip", state.toggle("laggy"))
        assertEquals(listOf("a:2", "empty:3"), keys())
    }

    /** The empty message must not cover rows the model is still drawing. */
    @Test
    fun theEmptyMessageOnlyCoversARowlessList() {
        assertFalse("rows held through a refresh", ServerListState.showEmpty("ready", 0, drawn = 3))
        assertFalse("the source has rows", ServerListState.showEmpty("ready", 157, drawn = 0))
        assertTrue(ServerListState.showEmpty("ready", 0, drawn = 0))
        assertFalse(ServerListState.showEmpty("loading", 0, drawn = 0))
        assertTrue(ServerListState.showEmpty("error", 0, drawn = 0))
    }

    /** A refresh that drops the chosen server must not leave the old address as the Join target. */
    @Test
    fun aChosenRowThatLeftTheListIsNoLongerTheJoinTarget() {
        state.accept(0, 2, listOf(row("a:1"), row("b:2")))
        assertTrue(state.select("b:2"))
        assertEquals("b:2", state.selected)

        repeat(2) {
            state.begin(1)
            state.accept(0, 1, listOf(row("a:1")))
            state.settle()
        }
        assertEquals(1, state.size)
        assertEquals("b is gone: no row to join", "", state.selected)
        val engine = mapOf("selection" to "single", "selected" to emptyList<Any?>())
        assertEquals(emptyList<Any?>(), state.merge(engine)["selected"])
    }

    /**
     * The whole engine sequence: open the list (cache empty) -> query -> batches ->
     * refresh -> tap a chip -> tap a row -> Join has a target. The renderer turns a
     * tap into toggle()/select()/merge(); this is the model's side of it.
     */
    @Test
    fun theWholeSequenceOpensQueriesRefreshesFiltersAndPicks() {
        val window = ListWindow("mp/servers", 50)
        screen(state, window)

        // open: the refresh has just cleared the engine's cache
        state.begin(0)
        screen(state, window)
        assertEquals(0, window.total)

        // the cache is back: the list asks for the rows it now knows about
        state.begin(3)
        screen(state, window)
        assertEquals(0..2, window.missing(0, 20))

        // the engine answers in windows: the rows reach the list as they arrive
        state.accept(0, 3, listOf(row("lan:1", net = "lan"), row("online:2", ping = 40, players = 4), row("slow:3", ping = 300, players = 1)))
        screen(state, window)
        assertEquals(3, window.loaded)
        assertEquals("online:2", window.row(1)!!.str("key"))

        // a refresh: a notice per change, the visible window asked for again
        state.begin(3)
        screen(state, window)
        window.refreshWindow(0, 2)
        assertEquals(0..2, window.missing(0, 2))
        state.accept(0, 3, listOf(row("lan:1", net = "lan"), row("online:2", ping = 35, players = 5), row("slow:3", ping = 310, players = 1)))
        screen(state, window)
        assertEquals(5, window.row(1)!!["cells"].obj().int("players"))

        // chip: Laggy is off in the engine; one tap turns it on, the row comes back
        state.sync("laggy", false)
        assertEquals(2, state.size)
        assertTrue(state.toggle("laggy"))
        assertEquals(3, state.size)
        state.accept(0, 3, listOf(row("lan:1", net = "lan"), row("online:2", ping = 35, players = 5), row("slow:3", ping = 310, players = 1)))
        screen(state, window)
        assertEquals(3, window.total)

        // row: chosen, and the join target is the row's address
        assertTrue(state.select("online:2"))
        val engine = mapOf("selection" to "single", "selected" to emptyList<Any?>(), "sort" to "ping", "desc" to false)
        assertEquals(listOf("online:2"), state.merge(engine)["selected"])
        assertEquals("online:2", state.selected)
        assertTrue("Join has a target", state.selected.isNotEmpty())
    }
}
