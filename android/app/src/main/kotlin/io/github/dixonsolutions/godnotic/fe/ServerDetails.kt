package io.github.dixonsolutions.godnotic.fe

/** Sizing policy for the native server detail panel's map picture. */
internal object ServerDetails {
    /** Keep the image wide while leaving room for details and actions in either orientation. */
    fun imageMaxHeightPx(widthPx: Int, heightPx: Int): Int =
        minOf(widthPx * 9 / 16, heightPx / 3).coerceAtLeast(0)

    fun facts(cells: Map<String, Any?>): String {
        val players = cells.int("players")
        val max = cells.int("max")
        val ping = cells.int("ping")
        return listOf(cells.str("map"), cells.str("mode").uppercase(),
            if (max > 0) "$players/$max" else "$players",
            if (ping > 0) "$ping ms" else "– ms").filter { it.isNotEmpty() }.joinToString(" · ")
    }
}
