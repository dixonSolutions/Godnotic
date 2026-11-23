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
        assertTrue(JoinFlow.GENERIC_DIM < 1f && JoinFlow.GENERIC_DIM >= 0.7f)
        assertEquals(Int.MAX_VALUE, JoinFlow.SHOT_WIDTH)
    }

    @Test fun downloadLine() {
        val t = JoinFlow.detail("downloading", "dlcache/wind2.pk3", 12.3 * 1048576, 40.0 * 1048576, 1.2 * 1048576, 1, "", "", 0, 0)
        assertEquals("Downloading wind2.pk3 12.3/40.0 MB  ·  1.2 MB/s", t)
        val many = JoinFlow.detail("downloading", "a.pk3", 512.0 * 1024, 0.0, 0.0, 3, "", "", 0, 0)
        assertEquals("Downloading a.pk3  ·  512 KB  ·  3 files", many)
    }

    @Test fun loadingLines() {
        assertEquals("Precaching models 12/40", JoinFlow.detail("loading", "", 0.0, 0.0, 0.0, 0, "models/x.md3", "models", 12, 40))
        assertEquals("Precaching sounds 3/9", JoinFlow.detail("preview", "", 0.0, 0.0, 0.0, 0, "", "sounds", 3, 9))
        assertEquals("Loading the map", JoinFlow.detail("loading", "", 0.0, 0.0, 0.0, 0, "*1", "", 0, 0))
        // a sound or model file is never the line: the map is
        assertEquals("Loading the map", JoinFlow.detail("loading", "", 0.0, 0.0, 0.0, 0, "loop_rain_04", "", 0, 0))
        assertEquals("Loading map wind2", JoinFlow.detail("loading", "", 0.0, 0.0, 0.0, 0, "Loading loop_rain_04", "", 0, 0, "wind2"))
        assertEquals("Loading map wind2", JoinFlow.detail("loading", "", 0.0, 0.0, 0.0, 0, "progs/bolt.mdl", "", 0, 0, "wind2"))
        assertEquals("Starting server", JoinFlow.detail("loading", "", 0.0, 0.0, 0.0, 0, "starting server", "", 0, 0))
    }

    @Test fun progressBar() {
        assertEquals(0.4, JoinFlow.progress("downloading", 0.4, 0.9, "", 0, 0), 1e-9)
        // the engine's loading stack value goes to the card now that its band is hidden
        assertEquals(0.6, JoinFlow.progress("loading", 0.0, 0.6, "models", 1, 10), 1e-9)
        // the stack says nothing: the precache count does
        assertEquals(0.25, JoinFlow.progress("loading", 0.0, 0.0, "models", 5, 20), 1e-9)
        assertEquals(0.01, JoinFlow.progress("loading", 0.0, 0.0, "models", 1, 283), 1e-9)
        assertTrue(JoinFlow.progress("signon", 0.0, 0.0, "models", 1, 283) > 0)
        // a load with nothing to report is determinate at its start; connecting has no bar yet
        assertEquals(0.01, JoinFlow.progress("loading", 0.0, 0.0, "", 0, 0), 1e-9)
        assertEquals(-1.0, JoinFlow.progress("connecting", 0.0, 0.0, "", 0, 0), 1e-9)
        assertEquals(-1.0, JoinFlow.progress("downloading", 0.0, 0.0, "", 0, 0), 1e-9)
    }
}

class JoinBarTest {
    @org.junit.Test fun overallIsOneContinuousBar() {
        // server data: the first fifth; downloads: the next two fifths; precache and load: the rest
        assertEquals(0.4, JoinFlow.overall("downloading", true, 0.5, 0.0), 1e-9)
        assertEquals(0.6, JoinFlow.overall("loading", true, 1.0, 0.0), 1e-9)
        assertEquals(0.8, JoinFlow.overall("loading", true, 1.0, 0.5), 1e-9)
        assertEquals(0.6, JoinFlow.overall("loading", false, 0.0, 0.5), 1e-9)
        assertEquals(-1.0, JoinFlow.overall("connecting", false, 0.0, 0.0), 1e-9)
    }

    @org.junit.Test fun barMovesWhileTheServersDataArrives() {
        // the wait for a stock server's first data (22 round trips) used to be a sweep with no number
        var last = -1.0
        for (kb in listOf(0, 1, 5, 11, 16, 21, 22)) {
            val b = JoinFlow.overall("preview", false, 0.0, -1.0, waiting = true, signon = JoinFlow.signonFraction(kb * 1024.0))
            assertTrue("bar at $kb KB ($b) must move", b > last)
            last = b
        }
        assertTrue(last <= JoinFlow.BAR_SIGNON)
        // more data than expected stretches the estimate instead of filling the bar
        assertTrue(JoinFlow.signonFraction(40 * 1024.0) <= 0.97)
        assertTrue(JoinFlow.signonFraction(40 * 1024.0) > JoinFlow.signonFraction(22 * 1024.0) - 1e-9)
        // the bar never goes back when the wait ends: the load starts where the data part stopped
        assertTrue(JoinFlow.overall("loading", false, 0.0, 0.0) >= JoinFlow.BAR_SIGNON)
    }
    @org.junit.Test fun downloadLineNamesBytesSpeedAndFiles() {
        val t = JoinFlow.downloading("dlcache/zzz-pack-weapons_01.pk3", 3.0 * 1048576, 22.8 * 1048576, 1042.4 * 1024, 4, 2)
        assertTrue(t, t.contains("3.0 of 22.8 MB") && t.contains("left") && t.contains("6 files"))
    }
}

/** The card's two lines (headline and numbers) in every phase of a join, and its docking. */
class JoinDetailTest {
    private val MB = 1048576.0

    @org.junit.Test fun waitingShowsBytesAndSeconds() {
        val l = JoinFlow.describe(JoinFlow.Snap(stage = "signon", waitS = 9, sigBytes = 24.0 * 1024))
        assertEquals("Receiving game data from the server", l.head)
        assertEquals("24 KB received  ·  9 s", l.detail)
        assertEquals("", JoinFlow.describe(JoinFlow.Snap(stage = "signon", waitS = 0)).detail)
    }

    @org.junit.Test fun downloadNamesFileCountMbSpeedAndTimeLeft() {
        val l = JoinFlow.describe(JoinFlow.Snap(
            stage = "downloading", dlFile = "dlcache/zzz-weapons_020.pk3", filesDone = 1, filesTotal = 5,
            dlBytes = 3.0 * MB, dlTotal = 22.8 * MB, speed = 1.0 * MB, running = 2, queued = 2))
        assertEquals("Downloading zzz-weapons_020.pk3", l.head)
        assertEquals("File 2 of 5  ·  3.0 of 22.8 MB  ·  1.0 MB/s  ·  0:19 left", l.detail)
        // one file: no count; unknown total: just what arrived
        val one = JoinFlow.describe(JoinFlow.Snap(stage = "downloading", dlFile = "a.pk3", filesTotal = 1, running = 1, dlBytes = 512.0 * 1024))
        assertEquals("Downloading a.pk3", one.head)
        assertEquals("512 KB received", one.detail)
    }

    @org.junit.Test fun precacheStepsAfterTheDownloads() {
        assertEquals("Map textures 120 / 218", JoinFlow.precacheStep("world", 120, 218))
        assertEquals("Models 40 / 332", JoinFlow.precacheStep("models", 40, 332))
        assertEquals("Sounds", JoinFlow.precacheStep("sounds", 0, 0))
        assertEquals("", JoinFlow.precacheStep("", 3, 4))
        val l = JoinFlow.describe(JoinFlow.Snap(stage = "loading", loadStage = "world", loadN = 120, loadM = 218, map = "colstart"))
        assertEquals("Loading map colstart", l.head)
        assertEquals("Map textures 120 / 218", l.detail)
        // older engines: the precache keys
        assertEquals("Models 5 / 20", JoinFlow.describe(JoinFlow.Snap(stage = "loading", precache = "models", precacheN = 5, precacheTotal = 20)).detail)
    }

    @org.junit.Test fun etaNeedsSpeedAndTotal() {
        assertEquals("", JoinFlow.eta(1.0, 10.0, 0.0))
        assertEquals("1:05 left", JoinFlow.eta(0.0, 65.0 * MB, 1.0 * MB))
    }

    @org.junit.Test fun cardKeepsClearOfSystemBars() {
        // bottom: the navigation bar, a cutout or the gesture area, whichever is larger
        assertEquals(listOf(0, 0, 0, 96), JoinFlow.cardInsets(0, 0, 0, 96, IntArray(4), 40).toList())
        assertEquals(listOf(0, 0, 0, 40), JoinFlow.cardInsets(0, 0, 0, 0, IntArray(4), 40).toList())
        // landscape: the bar moved to the side
        assertEquals(listOf(0, 24, 84, 0), JoinFlow.cardInsets(0, 24, 84, 0, intArrayOf(0, 0, 0, 0), 0).toList())
        assertEquals(listOf(60, 0, 0, 0), JoinFlow.cardInsets(0, 0, 0, 0, intArrayOf(60, 0, 0, 0), 0).toList())
    }

    @org.junit.Test fun downloadsShowWhateverTheStage() {
        assertEquals("downloading", JoinFlow.shownStage("preview", true))
        assertEquals("downloading", JoinFlow.shownStage("loading", true))
        assertEquals("preview", JoinFlow.shownStage("preview", false))
        assertEquals("connecting", JoinFlow.shownStage("connecting", true))
    }
}

/** The card's place, the server's own download, and the metered-link wording. */
class JoinDockAndDataTest {
    @org.junit.Test fun cardIsDockedAtTheBottomFromTheFirstFrame() {
        // Gravity.BOTTOM = 0x50 (80), CENTER_HORIZONTAL = 1: a bottom bit and never the top bit (0x30)
        assertEquals(81, JoinFlow.CARD_GRAVITY)
        assertEquals(0x50, JoinFlow.CARD_GRAVITY and 0x70)
    }

    @org.junit.Test fun theServersOwnDownloadIsNamedAndMeasured() {
        // csprogs.dat comes over the game connection (no curl): the engine reports it as a download of "csprogs.dat"
        assertEquals("the server's game code", JoinFlow.downloadName("csprogs.dat"))
        assertEquals("the server's game code", JoinFlow.downloadName("dlcache/csprogs.dat.4098806.38644"))
        assertEquals("wind2.pk3", JoinFlow.downloadName("dlcache/wind2.pk3"))
        val l = JoinFlow.describe(JoinFlow.Snap(stage = "downloading", dlFile = "csprogs.dat", filesTotal = 1, running = 1,
            dlBytes = 1.5 * 1048576, dlTotal = 3.3 * 1048576, speed = 330.0 * 1024))
        assertEquals("Downloading the server's game code", l.head)
        assertEquals("1.5 of 3.3 MB  ·  330 KB/s  ·  0:05 left", l.detail)
    }

    @org.junit.Test fun meteredLinkIsSaidAndCounted() {
        assertTrue(JoinFlow.metered(io.github.dixonsolutions.godnotic.Net.Kind.MOBILE))
        assertTrue(JoinFlow.metered(io.github.dixonsolutions.godnotic.Net.Kind.METERED_WIFI))
        assertTrue(JoinFlow.metered(io.github.dixonsolutions.godnotic.Net.Kind.MOBILE_WIFI_NO_INTERNET))
        assertFalse(JoinFlow.metered(io.github.dixonsolutions.godnotic.Net.Kind.UNMETERED))
        assertFalse(JoinFlow.metered(io.github.dixonsolutions.godnotic.Net.Kind.OFFLINE))
        assertEquals(24.0 * 1024 + 3 * 1048576.0, JoinFlow.used(24.0 * 1024, 3 * 1048576.0), 1e-9)
        assertEquals("", JoinFlow.usage(5e6, metered = false))
        assertEquals("mobile data", JoinFlow.usage(10.0 * 1024, metered = true))
        assertEquals("mobile data  ·  3.0 MB used", JoinFlow.usage(3.0 * 1048576, metered = true))
        val l = JoinFlow.describe(JoinFlow.Snap(stage = "loading", loadStage = "models", loadN = 4, loadM = 9, map = "boil", usedBytes = 25.0 * 1048576, metered = true))
        assertEquals("Loading map boil", l.head)
        assertEquals("Models 4 / 9  ·  mobile data  ·  25.0 MB used", l.detail)
        // the wait for the server's first data does not repeat its own byte count as "used"
        val w = JoinFlow.describe(JoinFlow.Snap(stage = "signon", waitS = 3, sigBytes = 12.0 * 1024, usedBytes = 12.0 * 1024, metered = true))
        assertEquals("12 KB received  ·  3 s  ·  mobile data", w.detail)
    }
}

/** The preview appears once and stays: no blank, no revert, only a better picture of the same map replaces it. */
class JoinPreviewSteadyTest {
    private fun shot(p: String, k: String, m: String = "boil") = JoinFlow.Shot(p, k, m)

    @org.junit.Test fun aSnapshotWithoutAPictureIsAGapNotAChange() {
        val cur = shot("/c/maps_boil.jpg", "list")
        assertEquals(cur, JoinFlow.nextShot(cur, JoinFlow.Shot()))
        assertEquals(cur, JoinFlow.nextShot(cur, JoinFlow.Shot("", "", "boil")))
    }

    @org.junit.Test fun firstPictureIsTakenAtOnce() {
        val s = shot("/c/maps_boil.jpg", "list")
        assertEquals(s, JoinFlow.nextShot(JoinFlow.Shot(), s))
    }

    @org.junit.Test fun onlyABetterKindReplacesAndNeverGoesBack() {
        val list = shot("/c/maps_boil.jpg", "list")
        val own = shot("/p/boil_signon.jpg", "map")
        val generic = shot("/g/generic.jpg", "generic")
        assertEquals(own, JoinFlow.nextShot(list, own))
        // the worse or equal one that follows does not take it back
        assertEquals(own, JoinFlow.nextShot(own, list))
        assertEquals(own, JoinFlow.nextShot(own, generic))
        assertEquals(own, JoinFlow.nextShot(own, shot("/p/boil_bsp.png", "map")))
        assertEquals(list, JoinFlow.nextShot(list, generic))
    }

    @org.junit.Test fun anotherMapsPictureReplacesAtOnceAndFadesNotWithinAMap() {
        val a = shot("/c/maps_boil.jpg", "map", "boil")
        val b = shot("/c/maps_atelier.jpg", "list", "atelier")
        assertEquals(b, JoinFlow.nextShot(a, b))
        assertFalse(JoinFlow.sameJoin(a, b))
        assertTrue(JoinFlow.sameJoin(shot("/c/x.jpg", "list"), shot("/p/y.jpg", "map")))
        assertTrue(JoinFlow.sameJoin(JoinFlow.Shot(), a))
    }

    @org.junit.Test fun wholeJoinSequenceChangesAtMostOnceAndNeverBlanks() {
        val seq = listOf(
            JoinFlow.Shot("", "", ""), shot("/c/maps_boil.jpg", "list"), JoinFlow.Shot("", "", "boil"), shot("/c/maps_boil.jpg", "list"),
            shot("/p/boil_signon.jpg", "map"), JoinFlow.Shot("", "", "boil"), shot("/c/maps_boil.jpg", "list"), JoinFlow.Shot("", "", ""),
        )
        var cur = JoinFlow.Shot()
        val shown = ArrayList<JoinFlow.Shot>()
        for (s in seq) { cur = JoinFlow.nextShot(cur, s); if (cur.path.isNotEmpty()) shown.add(cur) }
        assertEquals(listOf("/c/maps_boil.jpg", "/p/boil_signon.jpg"), shown.map { it.path }.distinct())
        assertEquals("/p/boil_signon.jpg", cur.path)
    }

    @org.junit.Test fun theWorldTakesOverOnlyAfterItStaysDrawable() {
        assertFalse(JoinFlow.worldShows(false, 0, 10_000))
        assertFalse(JoinFlow.worldShows(true, 1_000, 1_000 + JoinFlow.WORLD_SETTLE_MS - 1))
        assertTrue(JoinFlow.worldShows(true, 1_000, 1_000 + JoinFlow.WORLD_SETTLE_MS))
        assertTrue(JoinFlow.FADE_MS in 100..400)
    }
}

/** Owner's tablet (Hightime, minideck_v4r4): a picture from the first frame, a backdrop that fills the screen, friendly words, a bar that never goes back. */
class JoinFirstFrameTest {
    @org.junit.Test fun aPictureFromTheFirstFrame() {
        assertEquals("/e/a.jpg", JoinFlow.firstShot("/e/a.jpg", "/h/b.jpg", "/l/c.png"))
        assertEquals("/h/b.jpg", JoinFlow.firstShot("", "/h/b.jpg", "/l/c.png"))
        assertEquals("/l/c.png", JoinFlow.firstShot("", null, "/l/c.png"))
        assertEquals("", JoinFlow.firstShot("", null, null))
        // and the art is replaced by the engine's picture of that map, once, never back
        val art = JoinFlow.Shot("/m/minideck_v4r4.fallback.png", "generic", "minideck_v4r4")
        val real = JoinFlow.Shot("/fe_cache/maps_minideck_v4r4.jpg", "list", "minideck_v4r4")
        assertEquals(real, JoinFlow.nextShot(art, real))
        assertEquals(real, JoinFlow.nextShot(real, art))
    }

    @org.junit.Test fun connectingSaysConnectingNotTheEnginesRetryText() {
        val l = JoinFlow.describe(JoinFlow.Snap(stage = "connecting", status = "Connect: sending initial request, 8 retries left..."))
        assertEquals("Connecting…", l.head)
        assertEquals("", l.detail)
    }

    @org.junit.Test fun barNeverGoesBackOrReturnsToTheSweep() {
        var p = -1.0
        val seq = listOf(-1.0, 0.05, 0.19, 0.21, 0.5, 0.3, -1.0, 0.7, 0.4, 1.0)
        val shown = seq.map { p = JoinFlow.monotonic(p, it); p }
        assertEquals(listOf(-1.0, 0.05, 0.19, 0.21, 0.5, 0.5, 0.5, 0.7, 0.7, 1.0), shown)
        assertEquals(shown.sorted(), shown)
    }

    @org.junit.Test fun placeholderBackdropIsOneGradientForTheWholeScreen() {
        // the screen carries the gradient (FrontendHost.nameBackdrop); the map-name banner has no background of its own,
        // which left a darker band behind the card. Source guard: scripts/test-join-card-docking.sh
        assertTrue(JoinFlow.BACKDROP_TOP != JoinFlow.BACKDROP_BOTTOM)
    }
}

/** No wedge: rows under the card are the same backdrop as above it (a model of the composition; the render itself is checked on a device). */
class JoinNoWedgeTest {
    @org.junit.Test fun everyRowIsTheBackdropAndNothingElsePaintsBehindTheCard() {
        for (h in listOf(1280, 2000, 2400)) for (cardTop in listOf(h * 69 / 100, h * 80 / 100, h - 300)) {
            val ls = JoinFlow.layers(h, cardTop, h - 16)
            val painters = ls.filter { it.paints }
            val back = painters.first { it.name == "backdrop" }
            assertEquals(0, back.top); assertEquals(h, back.bottom)
            // behind/below the card the only background painter is the backdrop
            for (y in cardTop until h) assertTrue(painters.count { it.name != "card" && y >= it.top && y < it.bottom } == 1)
            assertTrue(ls.first { it.name == "banner" }.paints.not())
        }
    }

    @org.junit.Test fun theGradientHasNoStepAnywhereSoNoBandShowsAtTheCard() {
        val h = 2000
        var worst = 0
        for (y in 1 until h) {
            val a = JoinFlow.backdropColorAt(y - 1, h); val b = JoinFlow.backdropColorAt(y, h)
            for (s in intArrayOf(16, 8, 0)) worst = maxOf(worst, kotlin.math.abs(((a shr s) and 255) - ((b shr s) and 255)))
        }
        assertTrue("a step of $worst between neighbouring rows", worst <= 1)
        assertEquals(JoinFlow.BACKDROP_TOP, JoinFlow.backdropColorAt(0, h))
    }
}

/** Emulator frames (1920x1200, Hightime-like server): the card dropped at engine stage idle while the app said loading, and the screen was black for 28 s. */
class JoinHoldsThroughTheWorldLoadTest {
    @org.junit.Test fun cardStaysWhileTheAppSaysLoadingEvenAfterTheGrace() {
        assertTrue(JoinFlow.holdCard("idle", "loading", true, false, false, JoinFlow.FAIL_GRACE_MS + 30_000, false))
        // ...until the game is entered, a failure shows, or the player cancels
        assertFalse(JoinFlow.holdCard("idle", "ingame", true, false, false, 100, false))
        assertFalse(JoinFlow.holdCard("idle", "loading", true, false, false, 100, true))
        assertTrue(JoinFlow.holdCard("idle", "loading", true, true, false, 100, false))
        // a menu with nothing explaining it still goes after the grace
        assertFalse(JoinFlow.holdCard("idle", "menu", true, false, false, JoinFlow.FAIL_GRACE_MS + 50, false))
        // no card is raised from a stray loading state when there is none
        assertFalse(JoinFlow.holdCard("idle", "loading", false, false, false, 0, false))
    }
}
