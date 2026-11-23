package io.github.dixonsolutions.godnotic.fe

import java.io.File
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * A custom map's real picture with no host of ours: out of the pack the server itself serves the map from.
 *  1. [probePacks]: the first 3 round trips of a join (getchallenge, connect, the first fragments of the server's signon
 *     message) carry its `curl --pak <url>` lines; the server is then left with clc_disconnect, three times, exactly as the
 *     engine does, so no slot is held (checked against a dedicated server: "Disconnect by user").
 *  2. [pictureFromPack]: HTTP Range requests only: the end of the zip, its central directory when that is not in the
 *     end, then the one picture entry, inflated here. Never the pack.
 * scripts/mapshots-harvest.py does the same for the `mapshots` release; the engine's own version for a join is
 * touch_curlshot.h. Used for the info popup, for the one server the player chose, never for rows nobody opened.
 */
internal object ServerShots {
    /** Bytes of the packed picture a metered link accepts (the engine's touch_net_metered_shot_kb). */
    const val METERED_PACKED = 256 * 1024

    /** A map's name cut back at each '_' (sinister_full_r1, sinister_full, sinister), at least 4 letters: the picture of its first revision. */
    fun nameChain(map: String): List<String> {
        val out = arrayListOf(map)
        for (i in map.length - 1 downTo 5) if (map[i] == '_') out.add(map.substring(0, i))
        return out
    }

    /** One HTTP Range request: (status, bytes, total length) or null. Replaced in tests. */
    @Volatile var range: (String, String) -> Triple<Int, ByteArray, Long>? = ::httpRange

    private fun httpRange(url: String, rng: String): Triple<Int, ByteArray, Long>? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = MapShots.CONNECT_MS; c.readTimeout = MapShots.READ_MS; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Godnotic"); c.setRequestProperty("Range", rng)
            if (c.responseCode != 206) return null // a server that ignores Range would send the whole pack: never read
            val data = c.inputStream.use { capped(it, 5 * 1024 * 1024) } ?: return null
            val total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: return null
            return Triple(206, data, total)
        } finally { c.disconnect() }
    }

    private fun capped(s: InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384)
        while (true) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > max) return null }
        return out.toByteArray()
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    private fun u32be(b: ByteArray, o: Int) = ((b[o].toLong() and 255) shl 24) or ((b[o + 1].toLong() and 255) shl 16) or ((b[o + 2].toLong() and 255) shl 8) or (b[o + 3].toLong() and 255)

    private class Ent(val name: String, val method: Int, val csz: Long, val usz: Long, val off: Long, val crc: Long)

    /** A picture read from a pack: its bytes and the zip entry's CRC-32 (the pack's identity for the cache: a new pack with another picture has another CRC). */
    class Packed(val bytes: ByteArray, val crc: Long, val name: String)

    /** [map]'s picture (JPEG or PNG) out of the pack at [url]; at most [maxPacked] bytes of it are read. */
    fun pictureFromPack(url: String, map: String, maxPacked: Int = MapShots.MAX_BYTES): Packed? {
        val (st, tail, total) = range(url, "bytes=-65536") ?: return null
        if (st != 206) return null
        var e = -1
        for (i in tail.size - 22 downTo 0) if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tail.size) { e = i; break }
        if (e < 0) return null
        val size = u32(tail, e + 12); val off = u32(tail, e + 16)
        if (size <= 0 || size > 2 * 1024 * 1024 || off + size > total) return null
        val start = total - tail.size
        val cd = if (off >= start) tail.copyOfRange((off - start).toInt(), (off - start + size).toInt())
                 else (range(url, "bytes=" + off + "-" + (off + size - 1)) ?: return null).second
        val ents = HashMap<String, Ent>()
        var p = 0
        while (p + 46 <= cd.size && u32(cd, p) == 0x02014b50L) {
            val nl = u16(cd, p + 28); val el = u16(cd, p + 30); val cl = u16(cd, p + 32)
            val name = String(cd, p + 46, nl, Charsets.ISO_8859_1)
            if (u16(cd, p + 8) and 1 == 0) ents[name.lowercase()] = Ent(name, u16(cd, p + 10), u32(cd, p + 20), u32(cd, p + 24), u32(cd, p + 42), u32(cd, p + 16))
            p += 46 + nl + el + cl
        }
        val pick = nameChain(map).firstNotNullOfOrNull { b ->
            listOf("maps/", "levelshots/").firstNotNullOfOrNull { d ->
                listOf(".jpg", ".png").firstNotNullOfOrNull { x -> ents[d + b.lowercase() + x] }
            }
        } ?: return null
        if ((pick.method != 0 && pick.method != 8) || pick.csz <= 0 || pick.csz > maxPacked || pick.usz > 4 * 1024 * 1024) return null
        val (st2, blob, _) = range(url, "bytes=" + pick.off + "-" + (pick.off + 30 + pick.name.length + 512 + pick.csz)) ?: return null
        if (st2 != 206 || blob.size < 30 || u32(blob, 0) != 0x04034b50L) return null
        val data = 30 + u16(blob, 26) + u16(blob, 28)
        if (data + pick.csz > blob.size) return null
        val raw = blob.copyOfRange(data, (data + pick.csz).toInt())
        if (pick.method == 0) return Packed(raw, pick.crc, pick.name)
        val inf = java.util.zip.Inflater(true)
        return try {
            inf.setInput(raw)
            val out = ByteArray(pick.usz.toInt()); var n = 0
            while (n < out.size && !inf.finished()) { val k = inf.inflate(out, n, out.size - n); if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break; n += k }
            if (n == out.size) Packed(out, pick.crc, pick.name) else null
        } finally { inf.end() }
    }

    /** The `curl --pak <url>` URLs of the server's signon message (and the server is left again). [socketFor] is a fake in tests. */
    fun probePacks(host: String, port: Int, timeoutMs: Int = 4000, socketFor: () -> DatagramSocket = { DatagramSocket() }): List<String> {
        val s = socketFor()
        val addr = InetSocketAddress(host, port)
        val hdr = byteArrayOf(-1, -1, -1, -1)
        fun send(b: ByteArray) = s.send(DatagramPacket(b, b.size, addr))
        fun recv(ms: Int): ByteArray? {
            val p = DatagramPacket(ByteArray(65535), 65535)
            s.soTimeout = maxOf(ms, 10)
            return try { s.receive(p); p.data.copyOf(p.length) } catch (e: SocketTimeoutException) { null }
        }
        fun be(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        val urls = ArrayList<String>()
        val frags = java.util.TreeMap<Long, ByteArray>()
        try {
            send(hdr + "getchallenge".toByteArray())
            val end = System.currentTimeMillis() + timeoutMs
            var chal: String? = null
            while (chal == null && System.currentTimeMillis() < end) {
                val d = recv((end - System.currentTimeMillis()).toInt()) ?: break
                val t = String(d, Charsets.ISO_8859_1)
                if (d.size > 14 && d[0] == (-1).toByte() && t.startsWith("challenge ", 4)) chal = t.substring(14).split('\u0000', ' ', '\n')[0]
            }
            if (chal == null) return emptyList()
            send(hdr + ("connect\\protocol\\darkplaces 3\\protocols\\DP7 DP6 DP5 DP4 DP3 RMQ QW QUAKE\\challenge\\" + chal).toByteArray())
            var accepted = false
            while (!accepted && System.currentTimeMillis() < end) {
                val d = recv((end - System.currentTimeMillis()).toInt()) ?: break
                accepted = d.size >= 10 && d[0] == (-1).toByte() && String(d, 4, 6, Charsets.ISO_8859_1) == "accept"
            }
            if (!accepted) return emptyList()
            var seen = false
            while (System.currentTimeMillis() < end) {
                val d = recv(if (seen) 800 else (end - System.currentTimeMillis()).toInt()) ?: break
                if (d.size < 8 || d[0] == (-1).toByte()) continue
                val flags = u32be(d, 0); val seq = u32be(d, 4)
                if (flags and 0x10000L != 0L) {
                    send(be(0x20008L) + be(seq))
                    if (frags.put(seq, d.copyOfRange(8, minOf(d.size, (flags and 0xFFFF).toInt()))) == null) seen = true
                    val head = headOf(frags)
                    if (head.contains("curl --finish_autodownload") || head.length >= 8192) break
                }
            }
            val re = Regex("curl --pak [^\\n\\u0000]*?(https?://[^\\s\\u0000]+)\\n")
            for (mm in re.findAll(headOf(frags))) if (mm.groupValues[1] !in urls) urls.add(mm.groupValues[1])
        } catch (e: Exception) {
            // a probe is a courtesy: whatever was found
        } finally {
            for (i in 0 until 3) try { send(be(0x100009L) + be(i.toLong()) + byteArrayOf(2)) } catch (e: Exception) { } // clc_disconnect, as the engine
            s.close()
        }
        return urls
    }

    /** The fragments from the first, in order up to the first gap. */
    private fun headOf(frags: java.util.TreeMap<Long, ByteArray>): String {
        val sb = java.io.ByteArrayOutputStream()
        var next: Long? = null
        for ((k, v) in frags) { if (next != null && k != next) break; sb.write(v); next = k + 1 }
        return String(sb.toByteArray(), Charsets.ISO_8859_1)
    }

    /**
     * The popup's real picture: the pack the server serves the map from, read by Range. Returns the cached file or null.
     * [metered]: only a picture of at most [METERED_PACKED] bytes packed.
     */
    fun fromServer(map: String, host: String, port: Int, metered: Boolean): File? {
        val d = MapShots.dir ?: return null
        if (!MapShots.valid(map)) return null
        MapShots.cached(map)?.let { return it }
        val chain = nameChain(map).map { it.lowercase() }
        for (url in probePacks(host, port).take(8)) {
            val base = url.substringAfterLast('/').lowercase()
            if (chain.none { base.contains(it) }) continue // the pack that carries this map is named for it
            val got = try { pictureFromPack(url, map, if (metered) METERED_PACKED else MapShots.MAX_BYTES) } catch (e: Exception) { null } ?: continue
            if (!MapShots.looksLikeImage(got.bytes) || got.bytes.size > MapShots.MAX_BYTES) continue
            d.mkdirs()
            val tmp = File(d, map + ".jpg.part"); tmp.writeBytes(got.bytes)
            val f = File(d, map + ".jpg")
            if (tmp.renameTo(f)) {
                // which pack's picture it is: the zip entry's CRC-32 and the pack's name, so a second join shows it at once and a changed pack is told apart
                try { File(d, map + ".id").writeText(java.lang.Long.toHexString(got.crc) + " " + url.substringAfterLast('/')) } catch (e: Exception) { }
                return f
            }
        }
        return null
    }
}
