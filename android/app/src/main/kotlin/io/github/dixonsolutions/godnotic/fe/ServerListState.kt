package io.github.dixonsolutions.godnotic.fe

/**
 * The server browser's rows across a refresh, so the list never blinks.
 *
 * The engine re-reads its server cache four times a second and, whenever it
 * changed, broadcasts a `source` notice (a total, no rows) and then answers
 * the window the renderer asks for. A REFRESH resets that cache, so the
 * engine even broadcasts `total: 0` while it refills, once per answer after
 * that. None of these notices is a new list: this model keeps every row it
 * has seen keyed by address, holds the display positions, and:
 *
 * - a notice (`begin`) never clears and never dims: rows stay where they are,
 *   at the brightness they had, until the query running has finished;
 * - a window (`accept`) updates the rows it covers in place by address, so
 *   ping and players change without a row moving or being rebound;
 * - the query's end (`settle`: the engine stops refreshing) marks the rows
 *   the window did not send again `stale` (drawn dimmed) and keeps them for
 *   [graceRefreshes] completed queries (and at most [graceMillis] from the
 *   first one they missed), then removes them;
 * - a window's rows are ordered by the chosen column with ties broken by
 *   address, so equal pings never swap between refreshes, and not at all
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
        /** The query running sent this row's address again. */
        var answered = false
        /** Dimmed: it did not answer the last completed query. */
        var lost = false
        /** Completed queries in a row it did not answer. */
        var missed = 0
        /** When it was first missed (ms, [clock]). */
        var lostAt = 0L
        /** The query it first arrived in (a row that is new fades in). */
        var born = 0
    }

    private val items = HashMap<String, Item>()

    /** Display position -> address; null is a row asked for but not here yet. */
    private val slots = ArrayList<String?>()

    /** The query running (or the one that ran last): 0 before anything was listed. */
    private var query = 0
    private var running = false
    /** What the running query's windows covered: only those positions can be missed. */
    private var from = Int.MAX_VALUE
    private var to = -1
    /** One window held the whole listing (offset 0, every row): all other rows went. */
    private var whole = false
    private var received = false
    /** The source's row count from the last notice or window, drawn or not here yet. */
    private var want = 0

    /** The column chosen in the header (the engine orders the windows it sends). */
    var sort = ""
        private set
    var desc = false
        private set

    /** A finger on the list: nothing is re-ordered under it. */
    var hold = false

    /**
     * The chip filters (lan, empty, full, laggy): true includes those servers.
     * Held here, not in the chip views, so a diff that rebinds a chip or a
     * refresh that re-windows the list cannot lose a filter (and two taps on
     * the same chip flip it twice, never once plus a stale rebind). Absent
     * means the engine's default: everything included.
     */
    private val chips = HashMap<String, Boolean>()
    /** Chips the player has flipped: theirs beats any later value from the engine. */
    private val chosen = HashSet<String>()
    private var laggyMs = 150.0

    /** The row chosen in the list (its address), kept across refreshes. */
    private var selectedKey = ""
    /** The chosen address, while its row is still listed (a refresh can drop it). */
    val selected: String get() = selectedKey.takeIf { items.containsKey(it) } ?: ""

    /** Slot indices drawn, in slot order: the rows that pass the chips now. */
    private val shown = ArrayList<Int>()

    /** Rows the list draws, including the ones still in their grace period. */
    val size: Int get() = shown.size

    fun keyAt(position: Int): String? {
        val slot = shown.getOrNull(position) ?: return null
        return slots.getOrNull(slot)
    }

    /**
     * Whether the chip shows its servers (absent: the engine's default). LAN is
     * the exception: off means every server, so it starts off; Empty, Full and
     * Laggy start on, as the engine's own filters do.
     */
    fun include(chip: String): Boolean = chips[chip] ?: (chip != "lan")

    /** Has the player flipped this chip since the screen opened? */
    fun chosen(chip: String): Boolean = chip in chosen

    /**
     * The engine's value for a chip (its props' `value`, from `menu_slist_showempty`
     * and friends, or `mp.lan`), adopted until the player flips that chip. The
     * model's own default can disagree with the setting (Laggy starts off), and
     * then a tap on that chip looked dead: the model already believed it was on.
     */
    fun sync(chip: String, on: Boolean) {
        if (chip in chosen || chips[chip] == on) return
        chips[chip] = on
        rebuildShown()
    }

    /** A chip is narrowing the list, so its count is no longer the engine's. */
    val filtering: Boolean get() = chosen.isNotEmpty()

    /**
     * Flips one chip and re-filters at once: the rows on screen change in the
     * same frame, without waiting for the engine's next window. Returns the
     * chip's new state. A chip flipped twice is back where it began; a value
     * the engine sends meanwhile cannot flip it a second time.
     */
    fun toggle(chip: String): Boolean {
        val on = !include(chip)
        chosen.add(chip)
        chips[chip] = on
        rebuildShown()
        return on
    }

    fun setInclude(chip: String, on: Boolean) {
        chosen.add(chip)
        if (include(chip) == on) return
        chips[chip] = on
        rebuildShown()
    }

    /** Chooses the row (or clears it with an empty key); false when it did not change. */
    fun select(key: String): Boolean {
        if (key == selectedKey) return false
        selectedKey = key
        return true
    }

    /**
     * A row is drawn when it passes every chip. The engine's meanings, from
     * touch_uib_src.c build_servers: LAN keeps LAN and VPN servers (drops the
     * internet), Empty/Full include servers with free slots, Laggy includes
     * pings at or above [laggyMs]. `players` counts bots, so humans are
     * players minus bots, as there.
     */
    private fun matches(row: Map<String, Any?>): Boolean {
        val cells = row["cells"].obj()
        if (include("lan") && cells.str("net") == "internet") return false
        val players = cells.num("players")
        val humans = players - cells.num("bots")
        if (!include("empty") && humans <= 0) return false
        val max = cells.num("max")
        if (!include("full") && max > 0 && max - players <= 0) return false
        if (!include("laggy") && cells.num("ping") >= laggyMs) return false
        return true
    }

    private fun rebuildShown() {
        shown.clear()
        for ((i, key) in slots.withIndex()) {
            val item = key?.let { items[it] } ?: continue
            if (matches(item.row)) shown.add(i)
        }
    }

    fun choose(column: String, descending: Boolean) {
        sort = column
        desc = descending
    }

    /**
     * The list node's props with the locally chosen sort and row selection. The
     * engine keeps the rows; it does not keep what was just tapped, and its
     * props arrive four times a second, so a selection that lived only there
     * would be undone before the finger left the glass.
     */
    fun merge(props: Map<String, Any?>): Map<String, Any?> {
        var out = if (sort.isEmpty()) props else props + mapOf("sort" to sort, "desc" to desc)
        val sel = mergedSelection(props)
        if (sel != props["selected"].list()) out = out + ("selected" to sel)
        return out
    }

    /**
     * The source has a new revision: `total` rows, no window yet. A refresh, a
     * ping landing, a server removed: all the same here. Rows on screen stay,
     * at the brightness they had, until a window replaces them or the query
     * ends. Two notices in a row (the engine sends one per change) change
     * nothing that is drawn.
     */
    fun begin(total: Int) {
        received = true
        query++
        running = true
        from = Int.MAX_VALUE
        to = -1
        whole = false
        for (item in items.values) item.answered = false
        want = total
        resize(total)
        rebuildShown()
    }

    /**
     * A window of rows from the bridge: merged by address into the positions it
     * covers. Rows already on screen are never dropped here, whatever the total.
     */
    fun accept(offset: Int, total: Int, rows: List<Map<String, Any?>>) {
        received = true
        if (!running) begin(total)
        resize(maxOf(total, offset + rows.size))
        want = maxOf(total, offset + rows.size)
        for ((i, row) in rows.withIndex()) {
            val key = keyOf(row)
            if (key.isEmpty()) continue
            val item = items[key]
            if (item == null) {
                items[key] = Item(row).also { it.born = query }
                put(offset + i, key) // a new row takes its window position when that is free
            } else {
                if (item.row != row) item.row = row // same address, new ping/players: rebound in place
                if (slots.indexOf(key) < 0) put(offset + i, key) // known but never drawn: put it in
            }
            items.getValue(key).also { it.answered = true; it.lost = false; it.missed = 0; it.lostAt = 0L }
        }
        // a listing from offset 0 that holds every row covers the whole source;
        // a partial window covers only its own positions
        if (offset == 0 && rows.size == total) whole = true
        else if (rows.isNotEmpty()) {
            from = minOf(from, offset)
            to = maxOf(to, offset + rows.size - 1)
        }
        if (!hold) sortWindow(offset, rows.size)
        rebuildShown()
    }

    /**
     * The query finished (the engine says the list is no longer refreshing).
     * Rows the window did not send again are dimmed here and stay for the
     * grace period; the rest keep their brightness. Nothing is removed until
     * a row has missed [graceRefreshes] completed queries (or has been dimmed
     * for [graceMillis]), so a refresh that reports nothing at first never
     * clears the list.
     */
    fun settle() {
        if (items.isEmpty()) return
        val now = clock()
        if (!running) { prune(now); return }
        running = false
        // positions the query examined: everything when it delivered a complete
        // listing (or nothing at all), else the range its windows covered
        val all = whole || from > to
        val lo = if (all) 0 else from
        val hi = if (all) slots.size - 1 else to
        for ((position, key) in slots.withIndex()) {
            if (key == null || position < lo || position > hi) continue
            val item = items[key] ?: continue
            if (item.answered) {
                item.missed = 0
                item.lost = false
                item.lostAt = 0L
            } else if (!item.lost) {
                item.lost = true
                item.lostAt = now
                item.missed = 1
            } else {
                item.missed++
            }
        }
        prune(now)
    }

    /** Ends the grace period of rows no completed query confirmed: the list shrinks here. */
    private fun prune(now: Long) {
        var gone = false
        val it = items.entries.iterator()
        while (it.hasNext()) {
            val item = it.next().value
            if (!item.lost) continue
            if (item.missed >= graceRefreshes || now - item.lostAt >= graceMillis) {
                it.remove()
                gone = true
            }
        }
        if (!gone) return
        // close the gaps the removed rows left: the rest keep their order
        slots.removeAll { it == null || !items.containsKey(it) }
        rebuildShown()
    }

    /** A position's row, with `stale` and `fresh` for the renderer. */
    fun row(position: Int): Map<String, Any?>? {
        val key = slots.getOrNull(shown.getOrNull(position) ?: return null) ?: return null
        val item = items[key] ?: return null
        var row = item.row
        if (item.lost) row = row + ("stale" to true)
        if (item.born == query && query > 1) row = row + ("fresh" to true)
        return row
    }

    /** The loaded rows in contiguous runs (offset to rows), one list update each. */
    fun runs(): List<Pair<Int, List<Map<String, Any?>>>> {
        val out = ArrayList<Pair<Int, List<Map<String, Any?>>>>()
        var start = -1
        var batch: ArrayList<Map<String, Any?>>? = null
        for (i in 0 until shown.size) {
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

    /**
     * Publish the current snapshot to a list, whether it opened before or after
     * the reply. The count is the source's, not just the rows drawn: a list told
     * "0 rows" never asks for a window, so a browser opened while the engine's
     * cache was still empty stayed blank and never started its query.
     */
    fun publish(write: (Int, Int, List<Map<String, Any?>>) -> Unit) {
        if (!received) return
        val total = maxOf(size, want)
        val batches = runs()
        if (batches.isEmpty()) write(0, total, emptyList())
        else for ((offset, rows) in batches) write(offset, total, rows)
    }

    private fun resize(total: Int) {
        while (slots.size < total) slots.add(null)
    }

    /**
     * Where a row goes on screen. A row already drawn keeps its position: a
     * window updates rows by address and never moves one, so a server that has
     * not answered yet stays where it was instead of being evicted by a
     * shorter window. A new row takes its window position when that is free,
     * else the first free position, else the end.
     */
    private fun put(position: Int, key: String) {
        if (key.isEmpty() || slots.indexOf(key) >= 0) return
        val free = if (position >= 0 && position < slots.size && slots[position] == null) position else slots.indexOfFirst { it == null }
        if (free >= 0) slots[free] = key else slots.add(key)
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

    /** `merge` publishes the local selection over the engine's, so a refresh cannot drop it. */
    private fun mergedSelection(props: Map<String, Any?>): List<Any?> {
        val mine = selected // "" once its row left the list: not a target any more
        return if (mine.isEmpty()) props["selected"].list() else listOf(mine)
    }

    companion object {
        /**
         * The empty message covers only a list with nothing left to draw: a source
         * still holding rows through a refresh (or past a chip) is not empty, and
         * saying so covered a list that was about to fill.
         */
        fun showEmpty(state: String, total: Int, drawn: Int = 0) =
            state == "error" || (state == "ready" && total == 0 && drawn == 0)

        /**
         * The screen's "N servers · M players" with what this list actually
         * draws: the engine's summary counts every server it holds, whatever
         * the chips hide. Any text after the two numbers (" · refreshing…")
         * is kept; a line without them is left alone.
         */
        fun summary(text: String, servers: Int, players: Int): String {
            val m = Regex("^(\\d+) servers? · (\\d+) players?").find(text) ?: return text
            return "$servers servers · $players players" + text.substring(m.range.last + 1)
        }

        /** The players a list of rows holds, for [summary]. */
        fun players(rows: List<Map<String, Any?>>): Int =
            rows.sumOf { it["cells"].obj().num("players").toInt() - maxOf(0, it["cells"].obj().num("bots").toInt()) }

        fun keyOf(row: Map<String, Any?>) = row.str("key").ifEmpty { row["cells"].obj().str("address") }
    }
}
