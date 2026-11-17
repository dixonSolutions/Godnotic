package io.github.dixonsolutions.godnotic.fe

/**
 * The server browser's rows across a refresh, so the list never blinks.
 *
 * The engine re-reads its server cache four times a second and re-sends the
 * window on screen when anything changed (a ping, a player joining): that is
 * the same list, not a new one. This model keeps every row it has seen keyed
 * by address, holds the display positions, and:
 *
 * - a refresh (`begin`) never clears: rows stay where they are until their
 *   replacement arrives;
 * - a window's rows update in place by address, so ping and players change
 *   without a row moving or being rebound from scratch;
 * - a server that stops answering stays for [graceRefreshes] refreshes (and
 *   at most [graceMillis]) marked `stale` (drawn dimmed), then goes;
 * - the rows of one window are ordered by the chosen column with ties broken
 *   by address, so equal pings never swap between refreshes, and not at all
 *   while [hold] (a finger on the list) is set;
 * - a row first seen after the first listing is marked `fresh` (fades in).
 *
 * Plain Kotlin with no Android classes, so it runs in JVM unit tests.
 */
internal class ServerListState(
    private val graceRefreshes: Int = 2,
    private val graceMillis: Long = 10_000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private class Item(var row: Map<String, Any?>) {
        /** The refresh that last confirmed it. */
        var seen = 0
        /** True from the refresh it stopped answering in, until a window confirms it. */
        var lost = false
        /** When it first stopped answering (ms, [clock]). */
        var lostAt = 0L
        /** The refresh it first arrived in (a row that is new fades in). */
        var born = 0
    }

    private val items = HashMap<String, Item>()

    /** Display position -> address; null is a row asked for but not here yet. */
    private val slots = ArrayList<String?>()
    private var generation = 0
    private var received = false

    /** The column chosen in the header (the engine orders the windows it sends). */
    var sort = ""
        private set
    var desc = false
        private set

    /** A finger on the list: nothing is re-ordered under it. */
    var hold = false

    /** Rows the list draws, including the ones still in their grace period. */
    val size: Int get() = slots.size

    fun keyAt(position: Int): String? = slots.getOrNull(position)

    fun choose(column: String, descending: Boolean) {
        sort = column
        desc = descending
    }

    /** The list node's props with the locally chosen sort (the engine keeps the rows). */
    fun merge(props: Map<String, Any?>): Map<String, Any?> =
        if (sort.isEmpty()) props else props + mapOf("sort" to sort, "desc" to desc)

    /**
     * The source has a new revision: `total` rows now, no window yet. Every row
     * on screen stays, marked `stale` until a window confirms it.
     */
    fun begin(total: Int) {
        received = true
        generation++
        val now = clock()
        for (item in items.values) if (!item.lost) {
            item.lost = true
            item.lostAt = now
        }
        resize(total)
    }

    /**
     * A window of rows from the bridge: merged by address into the positions it
     * covers. Rows already on screen are never dropped here, whatever the total.
     */
    fun accept(offset: Int, total: Int, rows: List<Map<String, Any?>>) {
        received = true
        if (generation == 0) generation = 1 // the first listing: nothing to fade in
        val now = clock()
        resize(maxOf(total, offset + rows.size))
        for ((i, row) in rows.withIndex()) {
            val key = keyOf(row)
            if (key.isEmpty()) continue
            val item = items[key]
            if (item == null) {
                items[key] = Item(row).also { it.born = generation }
            } else if (item.row != row) {
                item.row = row // same address, new ping/players: rebound in place
            }
            items.getValue(key).also { it.seen = generation; it.lost = false; it.lostAt = 0L }
            place(offset + i, key)
        }
        if (total < slots.size) {
            // past the new end: keep them for the grace period, dimmed
            for (i in total until slots.size) slots[i]?.let { k ->
                items[k]?.let { if (it.seen < generation && !it.lost) { it.lost = true; it.lostAt = now } }
            }
        }
        if (!hold) sortWindow(offset, rows.size)
    }

    /** Ends the grace period of rows no window confirmed: the list shrinks here. */
    fun settle() {
        if (items.isEmpty()) return
        val now = clock()
        var gone = false
        val it = items.entries.iterator()
        while (it.hasNext()) {
            val item = it.next().value
            if (!item.lost) continue
            if (generation - item.seen >= graceRefreshes || now - item.lostAt >= graceMillis) {
                it.remove()
                gone = true
            }
        }
        if (!gone) return
        for (i in slots.indices) if (slots[i]?.let { !items.containsKey(it) } == true) slots[i] = null
        while (slots.isNotEmpty() && slots.last() == null) slots.removeAt(slots.size - 1)
    }

    /** A position's row, with `stale` and `fresh` for the renderer. */
    fun row(position: Int): Map<String, Any?>? {
        val key = slots.getOrNull(position) ?: return null
        val item = items[key] ?: return null
        var row = item.row
        if (item.seen < generation) row = row + ("stale" to true)
        if (item.born == generation && generation > 1) row = row + ("fresh" to true)
        return row
    }

    /** The loaded rows in contiguous runs (offset to rows), one list update each. */
    fun runs(): List<Pair<Int, List<Map<String, Any?>>>> {
        val out = ArrayList<Pair<Int, List<Map<String, Any?>>>>()
        var start = -1
        var batch: ArrayList<Map<String, Any?>>? = null
        for (i in 0 until slots.size) {
            val row = row(i)
            if (row == null) {
                if (batch != null) out.add(start to batch)
                batch = null
            } else {
                if (batch == null) {
                    start = i
                    batch = ArrayList()
                }
                batch.add(row)
            }
        }
        if (batch != null) out.add(start to batch)
        return out
    }

    /** Publish the current snapshot to a list, whether it opened before or after the reply. */
    fun publish(write: (Int, Int, List<Map<String, Any?>>) -> Unit) {
        if (!received) return
        val batches = runs()
        if (batches.isEmpty()) write(0, size, emptyList())
        else for ((offset, rows) in batches) write(offset, size, rows)
    }

    private fun resize(total: Int) {
        while (slots.size < total) slots.add(null)
    }

    /** A window is contiguous: its keys go at their own positions, never elsewhere. */
    private fun place(position: Int, key: String) {
        if (position < 0) return
        while (slots.size <= position) slots.add(null)
        if (slots[position] == key) return
        val old = slots.indexOf(key)
        if (old >= 0) slots[old] = null
        slots[position] = key
    }

    /** Orders one window by the chosen column; ties by address, so it repeats exactly. */
    private fun sortWindow(offset: Int, count: Int) {
        if (sort.isEmpty() || count < 2) return
        val keys = (offset until minOf(offset + count, slots.size)).mapNotNull { slots[it] }
        if (keys.size < 2) return
        val rows = keys.mapNotNull { items[it]?.row }
        if (rows.size != keys.size) return
        val ordered = rows.sortedWith { a, b -> compare(a, b) }.map { keyOf(it) }
        if (ordered == keys) return
        for ((i, key) in ordered.withIndex()) slots[offset + i] = key
    }

    private fun compare(a: Map<String, Any?>, b: Map<String, Any?>): Int {
        val ca = a["cells"].obj()
        val cb = b["cells"].obj()
        var r = when {
            sort == "ping" -> ping(ca).compareTo(ping(cb))
            ca[sort] is Number && cb[sort] is Number -> ca.num(sort).compareTo(cb.num(sort))
            ca[sort] is Boolean || cb[sort] is Boolean -> (if (ca.bool(sort)) 1 else 0) - (if (cb.bool(sort)) 1 else 0)
            else -> ca.str(sort).compareTo(cb.str(sort), ignoreCase = true)
        }
        if (desc) r = -r
        if (r == 0) r = keyOf(a).compareTo(keyOf(b)) // the same order every refresh
        return r
    }

    /** A ping of 0 is unknown: it sorts after every real one, whichever way the column runs. */
    private fun ping(cells: Map<String, Any?>): Double = cells.num("ping").let { if (it <= 0.0) Double.MAX_VALUE else it }

    companion object {
        fun showEmpty(state: String, total: Int) = state == "error" || (state == "ready" && total == 0)

        fun keyOf(row: Map<String, Any?>) = row.str("key").ifEmpty { row["cells"].obj().str("address") }
    }
}
