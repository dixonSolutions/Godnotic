package io.github.dixonsolutions.godnotic.fe

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server browser's per-update work, measured on the JVM (no Android):
 * a source notice plus a full 600-row window (what a refresh does four times a
 * second), a search keystroke, and a chip toggle. It prints microseconds per
 * update so the before/after numbers of a performance change are comparable,
 * and fails only on an order-of-magnitude regression, not on the machine's speed.
 */
class ServerListBenchmarkTest {
    private fun rows(n: Int) = (0 until n).map { i ->
        mapOf("key" to "10.0.${i / 250}.${i % 250}:26000",
            "cells" to mapOf("address" to "10.0.${i / 250}.${i % 250}:26000", "name" to "Server $i",
                "ping" to (20 + i % 300).toDouble(), "players" to (i % 16).toDouble(),
                "max" to 16.0, "bots" to 0.0, "net" to if (i % 17 == 0) "lan" else "internet"))
    }

    private fun time(label: String, iterations: Int, body: () -> Unit): Double {
        repeat(5) { body() }
        val t0 = System.nanoTime()
        repeat(iterations) { body() }
        val us = (System.nanoTime() - t0) / 1000.0 / iterations
        println("srvlist-bench $label: ${"%.1f".format(us)} us/update")
        return us
    }

    @Test fun sixHundredRowsRefreshSearchAndChip() {
        val n = 600
        val all = rows(n)
        val state = ServerListState()
        val update = time("600 rows notice+window+publish", 100) {
            state.begin(n)
            state.accept(0, n, all)
            state.publish { _, _, _ -> }
        }
        val search = time("600 rows search keystroke", 200) {
            state.setSearch("Server 5")
            state.setSearch("")
        }
        state.accept(0, n, all)
        val chip = time("600 rows chip toggle", 200) {
            state.toggle("lan")
            state.toggle("lan")
        }
        assertTrue("a 600-row update took ${update}us; the list bound is 20ms", update < 20_000)
        assertTrue("a search keystroke took ${search}us", search < 20_000)
        assertTrue("a chip toggle took ${chip}us", chip < 20_000)
    }
}
