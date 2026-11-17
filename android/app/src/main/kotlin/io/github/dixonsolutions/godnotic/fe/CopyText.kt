package io.github.dixonsolutions.godnotic.fe

/**
 * What a long press puts on the clipboard, as plain text: one sentence about
 * a server, a setting's label and value, a release note. Pure Kotlin so the
 * formats are unit tested; the Android side of it (ClipboardManager, the
 * "Copied" toast) is in [Kit.copyTo].
 */
object CopyText {
    /** Text as it goes on the clipboard: trimmed, and never empty. */
    fun clip(text: CharSequence): String = text.toString().trim()

    /**
     * A server as one line: "name (address) · map · mode · 3/16 · 42 ms".
     * The address always follows the name, because that is what a player
     * pastes to a friend; a row with no key falls back to its cells.
     */
    fun server(cells: Map<String, Any?>, key: String = ""): String {
        val name = cells.str("name").ifEmpty { cells.str("title") }
        val address = cells.str("address").ifEmpty { key }
        val bits = ArrayList<String>()
        bits.add(
            when {
                name.isNotEmpty() && address.isNotEmpty() -> "$name ($address)"
                name.isNotEmpty() -> name
                else -> address
            }
        )
        cells.str("map").takeIf { it.isNotEmpty() }?.let { bits.add(it) }
        cells.str("mode").takeIf { it.isNotEmpty() }?.let { bits.add(it) }
        val players = cells.num("players")
        val max = cells.num("max")
        if (players > 0 || max > 0) bits.add("${players.toInt()}/${max.toInt()}")
        val ping = cells.num("ping")
        if (ping > 0) bits.add("${ping.toInt()} ms")
        cells.str("version").takeIf { it.isNotEmpty() }?.let { bits.add(it) }
        return bits.filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** A setting or detail as one line: "Label: value" (just one when the other is empty). */
    fun line(label: String, value: String): String =
        if (label.isEmpty()) value else if (value.isEmpty()) label else "$label: $value"
}
