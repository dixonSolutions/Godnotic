package io.github.dixonsolutions.godnotic.fe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the join card says and shows (JoinFlow): picture, details, a real progress bar. */
class JoinCardTest {
    @Test fun neverTheLogo() {
        for (stage in listOf("connecting", "downloading", "loading", "signon", "preview"))
            assertFalse(JoinFlow.banner(stage, previewSeen = false))
    }

    @Test fun cardShowsAtOnceWithAPicture() {
        // a generic picture exists from the first connecting snapshot: no settle wait, no black page
        assertTrue(JoinFlow.card("connecting", joinDialog = false, settled = false, hasCard = false, shot = true))
        assertTrue(JoinFlow.showShot("/x/maps_foo.jpg", world = false))
        assertFalse(JoinFlow.showShot("/x/maps_foo.jpg", world = true))
        assertTrue(JoinFlow.generic("generic"))
        assertFalse(JoinFlow.generic("map"))
        assertTrue(JoinFlow.GENERIC_DIM < 1f)
    }

    @Test fun downloadLine() {
        val t = JoinFlow.detail("downloading", "dlcache/wind2.pk3", 12.3 * 1048576, 40.0 * 1048576, 1.2 * 1048576, 1, "", "", 0, 0)
        assertEquals("Downloading wind2.pk3  ·  12.3 MB of 40.0 MB  ·  1.2 MB/s", t)
        val many = JoinFlow.detail("downloading", "a.pk3", 512.0 * 1024, 0.0, 0.0, 3, "", "", 0, 0)
        assertEquals("Downloading a.pk3  ·  512 KB  ·  3 files", many)
    }

    @Test fun loadingLines() {
        assertEquals("Precaching models 12/40", JoinFlow.detail("loading", "", 0.0, 0.0, 0.0, 0, "models/x.md3", "models", 12, 40))
        assertEquals("Precaching sounds 3/9", JoinFlow.detail("preview", "", 0.0, 0.0, 0.0, 0, "", "sounds", 3, 9))
        assertEquals("Loading the map", JoinFlow.detail("loading", "", 0.0, 0.0, 0.0, 0, "*1", "", 0, 0))
        assertEquals("Loading bolt", JoinFlow.detail("loading", "", 0.0, 0.0, 0.0, 0, "progs/bolt.mdl", "", 0, 0))
    }

    @Test fun progressBar() {
        assertEquals(0.4, JoinFlow.progress("downloading", 0.4, 0.9, "", 0, 0), 1e-9)
        // the engine's loading stack value goes to the card now that its band is hidden
        assertEquals(0.6, JoinFlow.progress("loading", 0.0, 0.6, "models", 1, 10), 1e-9)
        // the stack says nothing: the precache count does
        assertEquals(0.25, JoinFlow.progress("loading", 0.0, 0.0, "models", 5, 20), 1e-9)
        // nothing yet: indeterminate
        assertEquals(-1.0, JoinFlow.progress("connecting", 0.0, 0.0, "", 0, 0), 1e-9)
        assertEquals(-1.0, JoinFlow.progress("downloading", 0.0, 0.0, "", 0, 0), 1e-9)
    }
}
