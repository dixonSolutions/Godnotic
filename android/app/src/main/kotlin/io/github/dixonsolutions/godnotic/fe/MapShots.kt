package io.github.dixonsolutions.godnotic.fe

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * A picture for a map that is not installed (a server's custom map), for the info popup and the join card. Never generated
 * art or the map's name as text: a file fetched before (dir/<map>.jpg) at once; else a neutral loading state while a small
 * picture is fetched (the picture host first, then the server's own pack: [ServerShots]); if nobody has one, the box closes up.
 * A map nobody has a picture for is not asked again for [NEGATIVE_TTL_MS]. The list prefetches the first rows' maps only on
 * an unmetered link; the one the player chose is fetched whatever the link (at most [MAX_BYTES]). The same file is what the
 * join card starts with, so the picture does not change when the join begins.
 */
internal object MapShots {
    const val MAX_BYTES = 400 * 1024
    const val NEGATIVE_TTL_MS = 6 * 3600_000L
    const val CONNECT_MS = 8000 // a map host over TLS on a mobile link can take ten seconds to answer
    const val READ_MS = 12000

    /** Printf templates with the map name; the first host that has the picture wins. */
    @Volatile var sources: List<String> = listOf("https://github.com/dixonSolutions/Godnotic-data/releases/download/mapshots/%s.jpg")
    @Volatile var dir: File? = null
    /** Opens a URL for reading (null when it has no picture): replaced in tests. */
    @Volatile var open: (String) -> InputStream? = ::httpOpen

    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "mapshots").apply { isDaemon = true } }
    private val inflight = HashSet<String>()

    /** Names that are safe as a file name and in a URL: no path, no dots-only, no spaces. */
    fun valid(map: String): Boolean = map.length in 1..64 && map.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' } 

    fun cached(map: String): File? = dir?.takeIf { valid(map) }?.let { File(it, "$map.jpg") }?.takeIf { it.isFile && it.length() > 0 }

    /** The kind the card gives a picture file: the art is a stand-in, a fetched map picture is the map's own. */
    fun kindOf(path: String): String = when {
        dir?.let { path.startsWith(it.path) } == true -> "map"
        else -> "list"
    }

    /** Whether a prefetch for rows nobody chose is allowed: not on a metered link. */
    fun prefetchAllowed(metered: Boolean): Boolean = !metered

    private fun httpOpen(url: String): InputStream? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = CONNECT_MS; c.readTimeout = READ_MS; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Godnotic")
        if (c.responseCode != 200) { c.disconnect(); return null }
        if (c.contentLengthLong > MAX_BYTES) { c.disconnect(); return null }
        return c.inputStream
    }

    internal fun looksLikeImage(b: ByteArray) =
        b.size > 8 && ((b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) || (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()))

    /** Fetches [map]'s picture now (call off the main thread): the file, or null (and a note that it is not there). */
    fun fetch(map: String, now: Long = System.currentTimeMillis(), noteMissing: Boolean = true): File? {
        val d = dir ?: return null
        if (!valid(map)) return null
        cached(map)?.let { return it }
        d.mkdirs()
        val none = File(d, "$map.none")
        if (none.isFile && now - none.lastModified() < NEGATIVE_TTL_MS) return null
        for (tpl in sources) {
            try {
                val bytes = open(String.format(java.util.Locale.US, tpl, map))?.use { s ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16384)
                    while (true) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > MAX_BYTES) return@use null }
                    out.toByteArray()
                } ?: continue
                if (!looksLikeImage(bytes)) continue
                val tmp = File(d, "$map.jpg.part")
                tmp.writeBytes(bytes)
                val f = File(d, "$map.jpg")
                if (tmp.renameTo(f)) { none.delete(); return f }
            } catch (e: Exception) { /* the next source; a picture is a courtesy */ }
        }
        if (noteMissing) noteMissing(map, now)
        return null
    }

    fun noteMissing(map: String, now: Long = System.currentTimeMillis()) {
        val d = dir ?: return
        try { File(d, map + ".none").also { it.writeText(""); it.setLastModified(now) } } catch (e: Exception) { }
    }

    fun recentlyMissing(map: String, now: Long = System.currentTimeMillis()): Boolean =
        dir?.let { File(it, map + ".none") }?.let { it.isFile && now - it.lastModified() < NEGATIVE_TTL_MS } == true

    /**
     * What the popup runs for a map it has no picture of: the picture host first (a 404 is quick), then the server's
     * own pack ([ServerShots]). One failure note for both, so neither is asked again for hours.
     */
    fun fetchAllAsync(map: String, host: String, port: Int, metered: Boolean, onFail: () -> Unit = {}, done: (File) -> Unit) {
        synchronized(inflight) { if (!inflight.add(map)) return }
        pool.execute {
            try {
                val f = fetch(map, noteMissing = false) ?: (if (recentlyMissing(map)) null else ServerShots.fromServer(map, host, port, metered))
                if (f != null) done(f) else { noteMissing(map); onFail() }
            } finally { synchronized(inflight) { inflight.remove(map) } }
        }
    }

    /** [fetch] on the pool, once per map at a time; [done] runs on the pool thread with the file. */
    fun fetchAsync(map: String, done: (File) -> Unit = {}) {
        synchronized(inflight) { if (!inflight.add(map)) return }
        pool.execute {
            try { fetch(map)?.let(done) } finally { synchronized(inflight) { inflight.remove(map) } }
        }
    }
}
