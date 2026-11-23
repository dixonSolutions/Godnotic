package io.github.dixonsolutions.godnotic.fe

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/** A custom map's picture: fetched small, cached, never asked for twice, never a bare box. */
class MapShotsTest {
    private lateinit var tmp: File
    private val asked = ArrayList<String>()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 5, 6, 7, 8, 9)

    @Before fun setUp() {
        tmp = java.nio.file.Files.createTempDirectory("mapshots").toFile()
        MapShots.dir = tmp
        MapShots.sources = listOf("http://a/%s.jpg", "http://b/%s.jpg")
        asked.clear()
    }
    @After fun tearDown() { tmp.deleteRecursively(); MapShots.open = { null } }

    @Test fun firstHostWithThePictureWinsAndTheFileIsKept() {
        MapShots.open = { u -> asked.add(u); if (u.startsWith("http://b/")) ByteArrayInputStream(jpeg) else null }
        val f = MapShots.fetch("sinister_full_r1")
        assertNotNull(f)
        assertEquals(listOf("http://a/sinister_full_r1.jpg", "http://b/sinister_full_r1.jpg"), asked)
        assertEquals(f, MapShots.cached("sinister_full_r1"))
        // cached: no request at all
        asked.clear()
        assertEquals(f, MapShots.fetch("sinister_full_r1"))
        assertTrue(asked.isEmpty())
        assertEquals("map", MapShots.kindOf(f!!.path))
    }

    @Test fun aMapNobodyHasIsNotAskedAgainForHours() {
        MapShots.open = { u -> asked.add(u); null }
        val t = System.currentTimeMillis()
        assertNull(MapShots.fetch("nopic", t))
        assertEquals(2, asked.size)
        asked.clear()
        assertNull(MapShots.fetch("nopic", t + 60_000))
        assertTrue("no request inside the negative TTL", asked.isEmpty())
        assertNull(MapShots.fetch("nopic", t + MapShots.NEGATIVE_TTL_MS + 120_000).also { })
        assertEquals("asked again after the TTL", 2, asked.size)
    }

    @Test fun bigOrNotAnImageIsRefusedAndNeverKept() {
        MapShots.open = { ByteArrayInputStream(ByteArray(MapShots.MAX_BYTES + 10) { 0x41 }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }) }
        assertNull(MapShots.fetch("huge"))
        MapShots.open = { ByteArrayInputStream("<html>404</html>".toByteArray()) }
        assertNull(MapShots.fetch("html"))
        assertNull(MapShots.cached("huge")); assertNull(MapShots.cached("html"))
    }

    @Test fun onlyPlainMapNamesAreFetched() {
        MapShots.open = { u -> asked.add(u); ByteArrayInputStream(jpeg) }
        for (bad in listOf("", "../x", "a/b", "a b", "a?b", "é")) { assertFalse(bad, MapShots.valid(bad)); assertNull(MapShots.fetch(bad)) }
        assertTrue(asked.isEmpty())
        assertTrue(MapShots.valid("sinister_full_r1")); assertTrue(MapShots.valid("quark-ht"))
    }

    @Test fun listPrefetchSkipsAMeteredLinkButArtAndKindsAreStable() {
        assertTrue(MapShots.prefetchAllowed(false)); assertFalse(MapShots.prefetchAllowed(true))
        assertEquals("generic", MapShots.kindOf(File(tmp, "x.fallback.png").path))
        assertEquals("list", MapShots.kindOf("/data/fe_cache/maps_x.jpg"))
        // the card's first picture (the art, generic) is replaced by the engine's own list picture, then the map's: never the other way
        val art = JoinFlow.Shot("/m/x.fallback.png", "generic", "x")
        val eng = JoinFlow.Shot("/fe_cache/maps_x.jpg", "list", "x")
        assertEquals(eng, JoinFlow.nextShot(art, eng))
        assertEquals(eng, JoinFlow.nextShot(eng, art))
    }
}
