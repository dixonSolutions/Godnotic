package io.github.dixonsolutions.godnotic.fe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFitTest {
    private fun check(w: Int, h: Int) {
        val p = HomeFit.plan(w, h, 18, 3)
        // everything fits in the box, nothing scrolls
        assertTrue("fits $w x $h: $p", p.top >= 0 && p.top + p.tilesTop + p.tilesH <= h)
        // the group is centred (to a pixel)
        val total = p.tilesTop + p.tilesH
        assertTrue("centred $w x $h: $p", kotlin.math.abs(p.top - (h - total - p.top)) <= 1)
        assertTrue(p.bannerH > 0 && p.tilesH > 0)
    }

    @Test fun fitsEveryShape() {
        for ((w, h) in listOf(1104 to 1650, 1824 to 1000, 320 to 560, 700 to 300, 1920 to 1080, 800 to 800)) check(w, h)
    }

    @Test fun portraitStacksAndLandscapeIsOneRow() {
        assertTrue(HomeFit.plan(1104, 1650, 18, 3).stack)
        assertEquals(false, HomeFit.plan(1824, 1000, 18, 3).stack)
    }

    @Test fun bannerIsLargeAndScalesWithWidth() {
        val tablet = HomeFit.plan(1104, 1650, 18, 3).bannerH
        val phone = HomeFit.plan(320, 560, 18, 3).bannerH
        assertTrue("banner $tablet", tablet > 300)
        assertTrue(phone < tablet)
        // landscape: banner takes about a quarter of the height
        assertTrue(HomeFit.plan(1824, 1000, 18, 3).bannerH >= 250)
    }

    @Test fun toolbarStacksWhenNarrow() {
        assertEquals(false, HomeFit.sideBySide(1104, 720, 500, 20)) // tablet portrait: field on top, chips under it
        assertEquals(true, HomeFit.sideBySide(1824, 720, 500, 20)) // tablet landscape: beside
    }
}
