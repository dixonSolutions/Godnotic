package io.github.dixonsolutions.godnotic.fe

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * A picture for a map that is not installed (a server's custom map): the server list's info popup and the
 * join card had a bare placeholder for it. Three steps, cheapest first:
 *  1. a file fetched before (dir/<map>.jpg), at once;
 *  2. art made on the spot from the map's name ([colors], [fallback]), at once, so the box is never blank;
 *  3. a small picture fetched from [sources] (a mapshot host: only the image, never the pack), kept on disk,
 *     fading in over the art when it arrives. A map nobody has a picture for is not asked again for
 *     [NEGATIVE_TTL_MS]. The list prefetches the first rows' maps only on an unmetered link; the one
 *     the player chose is fetched whatever the link (at most [MAX_BYTES]).
 * The same file (or the art's) is what the join card starts with, so the picture does not change when the
 * join begins.
 */
internal object MapShots {
    const val MAX_BYTES = 400 * 1024
    const val NEGATIVE_TTL_MS = 6 * 3600_000L
    const val CONNECT_MS = 3000
    const val READ_MS = 4000

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

    /** The art's file (what the join card gets as its first picture when no real one is known). */
    fun fallbackFile(map: String): File? = dir?.takeIf { valid(map) }?.let { File(it, "$map.fallback.png") }

    /** The kind the card gives a picture file: the art is a stand-in, a fetched map picture is the map's own. */
    fun kindOf(path: String): String = when {
        path.endsWith(".fallback.png") -> "generic"
        dir?.let { path.startsWith(it.path) } == true -> "map"
        else -> "list"
    }

    /** Whether a prefetch for rows nobody chose is allowed: not on a metered link. */
    fun prefetchAllowed(metered: Boolean): Boolean = !metered

    /** Two dark colours from the name: the same map always looks the same. */
    fun colors(map: String): Pair<Int, Int> {
        var h = 0x811C9DC5.toInt()
        for (c in map) h = (h xor c.code) * 16777619
        val hue = ((h ushr 8) % 360).toFloat()
        fun rgb(hue: Float, s: Float, v: Float) = android.graphics.Color.HSVToColor(floatArrayOf(hue, s, v))
        return rgb(hue, 0.55f, 0.32f) to rgb((hue + 40f) % 360f, 0.65f, 0.12f)
    }

    /** Art for the box: a gradient in the map's colours, its name big, the game type small. */
    fun fallback(map: String, gametype: String, w: Int = 640, h: Int = 360): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val (a, b) = colors(map)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), a, b, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = 0x22FFFFFF
        c.drawCircle(w * 0.82f, h * 0.2f, h * 0.55f, p)
        p.color = 0xE6FFFFFF.toInt(); p.textAlign = android.graphics.Paint.Align.CENTER; p.isFakeBoldText = true
        p.textSize = (h * 0.14f).coerceAtMost(w * 0.9f / maxOf(map.length, 6) * 1.7f)
        c.drawText(map, w / 2f, h * 0.52f, p)
        if (gametype.isNotEmpty()) {
            p.isFakeBoldText = false; p.color = 0xB3FFFFFF.toInt(); p.textSize = h * 0.07f
            c.drawText(gametype.uppercase(), w / 2f, h * 0.52f + h * 0.12f, p)
        }
        return bmp
    }

    /** Writes the art to its file once and returns the path (the join card's first picture). */
    fun fallbackPath(map: String, gametype: String): String? {
        val f = fallbackFile(map) ?: return null
        if (!f.isFile) try {
            f.parentFile?.mkdirs()
            fallback(map, gametype).let { b -> f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) } }
        } catch (e: Exception) { return null }
        return f.path
    }

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
    fun fetchAllAsync(map: String, host: String, port: Int, metered: Boolean, done: (File) -> Unit) {
        synchronized(inflight) { if (!inflight.add(map)) return }
        pool.execute {
            try {
                val f = fetch(map, noteMissing = false) ?: (if (recentlyMissing(map)) null else ServerShots.fromServer(map, host, port, metered))
                if (f != null) done(f) else noteMissing(map)
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
