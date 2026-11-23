package io.github.dixonsolutions.godnotic.fe

/**
 * Which option of a single-choice group (segmented chips) shows as selected.
 *
 * The engine publishes the bound cvar's own value, which need not be one of the options: Bot skill offers
 * 1, 4, 7 and 10 while the cvar holds 2 (the stock default), 3, 5.5 and so on. With no exact match every
 * chip looked unselected. A numeric value that matches none takes the nearest option (the lower one on a
 * tie); a non-numeric one takes none.
 */
object ChoiceSelection {
    fun selected(optionValues: List<String>, current: String): String {
        if (current in optionValues) return current
        val cur = current.trim().toDoubleOrNull() ?: return ""
        var best: String? = null
        var bestDist = Double.MAX_VALUE
        for (v in optionValues) {
            val n = v.trim().toDoubleOrNull() ?: return "" // a group that is not numeric has no "nearest"
            val d = Math.abs(n - cur)
            if (d < bestDist) { bestDist = d; best = v }
        }
        return best ?: ""
    }
}
