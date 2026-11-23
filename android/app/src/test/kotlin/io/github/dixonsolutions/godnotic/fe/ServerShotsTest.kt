package io.github.dixonsolutions.godnotic.fe

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The popup's real picture from the server's own pack: Range reads only, a polite probe. */
class ServerShotsTest {
    private lateinit var tmp: File
    private val ranges = ArrayList<String>()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(3000) { (it * 7).toByte() }

    private fun pack(): ByteArray {
        val o = ByteArrayOutputStream()
        ZipOutputStream(o).use { z ->
            z.putNextEntry(ZipEntry("maps/sinister_full_r1.bsp")); z.write(ByteArray(20000) { 1 }); z.closeEntry()
            z.putNextEntry(ZipEntry("maps/sinister_full.jpg")); z.write(jpeg); z.closeEntry()
            z.putNextEntry(ZipEntry("maps/other.jpg")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
        }
        return o.toByteArray()
    }

    private fun fakeRange(p: ByteArray): (String, String) -> Triple<Int, ByteArray, Long>? = { _, r ->
        ranges.add(r)
        val spec = r.removePrefix("bytes=")
        if (spec.startsWith("-")) { val n = spec.substring(1).toInt(); Triple(206, p.copyOfRange(maxOf(0, p.size - n), p.size), p.size.toLong()) }
        else { val (a, b) = spec.split("-").map { it.toLong() }; Triple(206, p.copyOfRange(a.toInt(), minOf(b + 1, p.size.toLong()).toInt()), p.size.toLong()) }
    }

    @Before fun setUp() { tmp = java.nio.file.Files.createTempDirectory("srvshots").toFile(); MapShots.dir = tmp; ranges.clear() }
    @After fun tearDown() { tmp.deleteRecursively(); ServerShots.range = { _, _ -> null } }

    @Test fun aRevisionTakesTheFirstRevisionsPictureByRangeOnly() {
        ServerShots.range = fakeRange(pack())
        val got = ServerShots.pictureFromPack("http://x/sinister_full_r1.pk3", "sinister_full_r1")
        assertNotNull(got)
        assertTrue(got!!.bytes.contentEquals(jpeg))
        assertTrue("the entry's CRC identifies the picture", got.crc == java.util.zip.CRC32().also { it.update(jpeg) }.value)
        assertEquals("maps/sinister_full.jpg", got.name)
        assertTrue("only Range requests, at most 3: $ranges", ranges.all { it.startsWith("bytes=") } && ranges.size <= 3)
        assertNull(ServerShots.pictureFromPack("http://x/y.pk3", "nomap_r1"))
        assertEquals(listOf("sinister_full_r1", "sinister_full"), ServerShots.nameChain("sinister_full_r1").take(2))
    }

    @Test fun aBigPackedPictureIsRefusedOnAMeteredLink() {
        ServerShots.range = fakeRange(pack())
        assertNull(ServerShots.pictureFromPack("http://x/a.pk3", "sinister_full_r1", maxPacked = 10))
    }

    @Test fun aServerThatIgnoresRangeIsNeverRead() {
        val p = pack()
        ServerShots.range = { _, _ -> Triple(200, p, p.size.toLong()) }
        assertNull(ServerShots.pictureFromPack("http://x/a.pk3", "sinister_full_r1"))
    }

    @Test fun probeAsksPolitelyFindsThePackAndLeavesWithDisconnects() {
        val server = DatagramSocket(0, java.net.InetAddress.getLoopbackAddress())
        val got = java.util.Collections.synchronizedList(ArrayList<ByteArray>())
        val t = Thread {
            try {
                val buf = DatagramPacket(ByteArray(2048), 2048)
                var client: java.net.SocketAddress? = null
                server.soTimeout = 3000
                while (true) {
                    server.receive(buf); val d = buf.data.copyOf(buf.length); got.add(d); client = buf.socketAddress
                    val s = String(d, Charsets.ISO_8859_1)
                    fun reply(b: ByteArray) = server.send(DatagramPacket(b, b.size, client))
                    val h = byteArrayOf(-1, -1, -1, -1)
                    if (s.startsWith("getchallenge", 4)) reply(h + "challenge abc123\u0000".toByteArray())
                    else if (s.startsWith("connect", 4)) {
                        reply(h + "accept".toByteArray())
                        val text = "foo\ncurl --clear_autodownload\ncurl --pak --forthismap http://127.0.0.1:1/sinister_full_r1.pk3\ncurl --finish_autodownload\n"
                        val body = text.toByteArray(Charsets.ISO_8859_1)
                        val flags = 0x10000 or 0x80000 or (8 + body.size)
                        reply(byteArrayOf((flags shr 24).toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte(), 0, 0, 0, 0) + body)
                    }
                }
            } catch (e: Exception) { }
        }
        t.start()
        val urls = ServerShots.probePacks("127.0.0.1", server.localPort, 2000)
        Thread.sleep(300)
        server.close(); t.join(1000)
        assertEquals(listOf("http://127.0.0.1:1/sinister_full_r1.pk3"), urls)
        val kinds = got.map { String(it, Charsets.ISO_8859_1) }
        assertTrue(kinds.any { it.startsWith("getchallenge", 4) })
        assertTrue(kinds.any { it.startsWith("connect", 4) && it.contains("challenge\\abc123") })
        // an ack of the fragment, then clc_disconnect three times (unreliable packets whose payload is byte 2)
        assertTrue("the fragment is acknowledged", got.any { it.size == 8 && (it[1].toInt() and 2) != 0 })
        assertEquals("three disconnects", 3, got.count { it.size == 9 && it[8].toInt() == 2 && (it[1].toInt() and 0x10) != 0 })
    }

    @Test fun aServerThatDoesNotAnswerGivesNothingQuickly() {
        val dead = DatagramSocket(0, java.net.InetAddress.getLoopbackAddress()) // nobody reads it
        val t0 = System.currentTimeMillis()
        assertTrue(ServerShots.probePacks("127.0.0.1", dead.localPort, 400).isEmpty())
        dead.close()
        assertTrue("gave up in time", System.currentTimeMillis() - t0 < 2500)
        MapShots.dir = null
        assertNull("no cache dir, no fetch", ServerShots.fromServer("sinister_full_r1", "127.0.0.1", 1, false))
        MapShots.dir = tmp
        assertNull("a bad map name is never fetched", ServerShots.fromServer("../x", "127.0.0.1", 1, false))
    }
}
