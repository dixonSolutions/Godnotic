package io.github.dixonsolutions.godnotic.fe

/** The server list's sort is controlled locally; the engine owns selection and filters. */
internal class ServerListState {
    private var sort: Pair<String, Boolean>? = null

    fun choose(column: String, descending: Boolean) { sort = column to descending }

    fun merge(props: Map<String, Any?>): Map<String, Any?> {
        val current = sort ?: return props
        return props + mapOf("sort" to current.first, "desc" to current.second)
    }
}
