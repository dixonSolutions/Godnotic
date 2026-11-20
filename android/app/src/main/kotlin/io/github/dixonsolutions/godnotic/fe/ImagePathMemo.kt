package io.github.dixonsolutions.godnotic.fe

/**
 * What the engine answered for each picture reference (`pk3:maps/bromine` -> a file in its
 * uib-cache), kept between sessions: a card whose picture was shown last time has the file's path
 * in its first frame and draws it with the card, instead of asking the engine and drawing a
 * placeholder for the round trip. Plain logic (the file reading is the caller's): a JVM test
 * pins it. Newest answers last; the oldest go when it holds more than [max].
 */
class ImagePathMemo(private val max: Int = 200) {
    private val map = LinkedHashMap<String, String>()

    val size: Int get() = map.size

    fun get(ref: String): String? = map[ref]

    /** True when this changed what is remembered (the caller then saves). */
    fun put(ref: String, path: String): Boolean {
        if (ref.isEmpty() || path.isEmpty() || ref.contains('\t') || ref.contains('\n') || path.contains('\t') || path.contains('\n')) return false
        if (map[ref] == path) return false
        map.remove(ref)
        map[ref] = path
        while (map.size > max) map.remove(map.keys.first())
        return true
    }

    fun forget(ref: String) { map.remove(ref) }

    /** The newest [n] remembered entries whose reference starts with [prefix]: (ref, path), newest first. */
    fun recent(prefix: String, n: Int): List<Pair<String, String>> =
        map.entries.reversed().filter { it.key.startsWith(prefix) }.take(n).map { it.key to it.value }

    fun serialize(): String = map.entries.joinToString("") { "${it.key}\t${it.value}\n" }

    companion object {
        fun parse(text: String, max: Int = 200): ImagePathMemo {
            val m = ImagePathMemo(max)
            for (line in text.lineSequence()) {
                val t = line.indexOf('\t')
                if (t > 0 && t < line.length - 1) m.put(line.substring(0, t), line.substring(t + 1))
            }
            return m
        }
    }
}
