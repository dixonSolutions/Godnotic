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
        // landscape: the banner is small, a fifth of the height
        assertTrue(HomeFit.plan(1824, 1000, 18, 3).bannerH >= 150)
    }

    @Test fun landscapeBannerIsCappedAndCardsKeepTheRest() {
        // the tablet's landscape page: 1920x1200 less the bottom bar and padding
        for ((w, h) in listOf(1872 to 960, 1824 to 1000, 1280 to 520)) {
            val p = HomeFit.plan(w, h, 18, 3)
            assertTrue("banner ${p.bannerH} of $h", p.bannerH <= (h * 0.22f).toInt())
            assertTrue("cards ${p.tilesH} of $h", p.tilesH >= (h * 0.6f).toInt())
            assertTrue(p.top + p.tilesTop + p.tilesH <= h)
        }
    }

    @Test fun toolbarStacksWhenNarrow() {
        assertEquals(false, HomeFit.sideBySide(1104, 720, 500, 20)) // tablet portrait: field on top, chips under it
        assertEquals(true, HomeFit.sideBySide(1824, 720, 500, 20)) // tablet landscape: beside
    }

    @Test fun theServerCardHoldsWholeLinesInEveryShape() {
        // the card's lines get its height less the title, summary and padding (about 120 dp): a tablet's
        // landscape and portrait cards show several whole lines, and a phone's at least one
        for ((w, h, d) in listOf(Triple(1920, 1200, 2f), Triple(1200, 1920, 2f), Triple(2400, 1080, 2.75f), Triple(1080, 2400, 2.75f))) {
            val p = HomeFit.plan(w, h, (16 * d).toInt(), 3)
            val cardW = if (p.stack) w else (w - 2 * (16 * d).toInt()) / 3
            val rowPx = Math.round(CardTags.tableRowDp(cardW / d) * d)
            val cardH = if (p.stack) (p.tilesH - 2 * (16 * d).toInt()) / 3 else p.tilesH
            val avail = cardH - (120 * d).toInt()
            val n = CardTags.fitRows(avail, rowPx, CardTags.MAX_ROWS)
            assertTrue("$w x $h: $n lines in $avail px", n >= 1)
            assertTrue("$w x $h: lines never overflow", n * rowPx <= avail)
        }
    }

    /** The lines the server-list card holds in a plan: its height less the overhead, at the row height it picks. */
    private fun mpLines(p: HomeFit.Plan, w: Int, d: Float, gap: Int): Int {
        val cardW = (w - 2 * gap) / 3
        val natural = Math.round(CardTags.tableRowDp(cardW / d) * d)
        val avail = p.mpH - (HomeFit.MP_OVERHEAD_DP * d).toInt()
        return CardTags.fitRows(avail, CardTags.rowPxFor(avail, natural, d), CardTags.MAX_ROWS)
    }

    @Test fun theServerCardShowsTenLinesOnTheOwnersTablet() {
        val d = 2f
        val gap = (9 * d).toInt()
        // the whole 1920x1200 screen, and the page's real box (less the bottom bar and padding)
        for ((w, h) in listOf(1920 to 1200, 1872 to 960)) {
            val p = HomeFit.plan(w, h, gap, 3, true, d)
            assertTrue("$w x $h: ${mpLines(p, w, d, gap)} lines in $p", mpLines(p, w, d, gap) >= 10)
            assertTrue("fits", p.top >= 0 && p.top + p.tilesTop + p.tilesH <= h)
            assertTrue("banner stays", p.bannerH >= (h * HomeFit.MP_MIN_BANNER).toInt())
            // the other cards keep their size (never above 1.25 of their width)
            val plain = HomeFit.plan(w, h, gap, 3)
            assertTrue("other cards: ${p.cardH} vs ${plain.tilesH}", p.cardH >= plain.tilesH && p.cardH <= ((w - 2 * gap) / 3 * 1.25f).toInt())
        }
        // without the server card (or upright) nothing changes
        assertEquals(HomeFit.plan(1920, 1200, 18, 3).tilesH, HomeFit.plan(1920, 1200, 18, 3, false, d).tilesH)
        assertEquals(HomeFit.plan(1200, 1920, 18, 3).tilesH, HomeFit.plan(1200, 1920, 18, 3, true, d).tilesH)
    }

    @Test fun aShortCardShrinksItsLinesRatherThanShowingFewer() {
        assertEquals(88, CardTags.rowPxFor(2000, 88, 2f))
        assertEquals(60, CardTags.rowPxFor(600, 88, 2f))
        assertEquals(60, CardTags.rowPxFor(300, 88, 2f)) // never below the minimum
    }
}
