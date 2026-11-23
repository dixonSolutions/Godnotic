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
            val natural = Math.round(CardTags.tableRowDp(cardW / d) * d)
            val cardH = if (p.stack) (p.tilesH - 2 * (16 * d).toInt()) / 3 else p.tilesH
            val avail = cardH - (120 * d).toInt()
            val rowPx = CardTags.rowPxFor(avail, natural, d) // the card shrinks its lines (down to the minimum) to fit
            val n = CardTags.fitRows(avail, rowPx, CardTags.MAX_ROWS)
            assertTrue("$w x $h: $n lines in $avail px", n >= 1)
            assertTrue("$w x $h: lines never overflow", n * rowPx <= avail)
        }
    }

    /** The lines the server-list card holds in a plan: its height less the title, summary and padding (about 100 dp), at the row height it picks. */
    private fun mpLines(p: HomeFit.Plan, w: Int, d: Float, gap: Int): Int {
        val cardW = (w - 2 * gap) / 3
        val natural = Math.round(CardTags.tableRowDp(cardW / d) * d)
        val avail = p.mpH - (100 * d).toInt()
        return CardTags.fitRows(avail, CardTags.rowPxFor(avail, natural, d), CardTags.MAX_ROWS)
    }

    @Test fun theServerCardIsAsTallAsTheOtherCardsInLandscape() {
        val d = 2f
        val gap = (9 * d).toInt()
        for ((w, h) in listOf(1920 to 1200, 1872 to 960, 1824 to 1000, 2400 to 1080, 1280 to 520)) {
            val p = HomeFit.plan(w, h, gap, 3)
            assertEquals("$w x $h: $p", p.cardH, p.mpH)
            assertEquals("$w x $h: grid is the card height", p.cardH, p.tilesH)
            assertTrue("fits", p.top >= 0 && p.top + p.tilesTop + p.tilesH <= h)
        }
    }

    @Test fun theServerCardShowsTheLinesThatFitTheSharedHeight() {
        val d = 2f
        val gap = (9 * d).toInt()
        val p = HomeFit.plan(1872, 960, gap, 3)
        val n = mpLines(p, 1872, d, gap)
        // more than a few, never below the minimum line height, never past the card
        assertTrue("$n lines in $p", n in 5..CardTags.WANTED_ROWS)
        val cardW = (1872 - 2 * gap) / 3
        val natural = Math.round(CardTags.tableRowDp(cardW / d) * d)
        val avail = p.mpH - (100 * d).toInt()
        val rowPx = CardTags.rowPxFor(avail, natural, d)
        assertTrue("row ${rowPx}px", rowPx >= Math.round(CardTags.MIN_ROW_DP * d) && n * rowPx <= avail)
    }

    @Test fun portraitIsUnchanged() {
        // upright: the stacked cards, as before
        val p = HomeFit.plan(1200, 1920, 18, 3)
        assertTrue(p.stack)
        assertEquals(p.cardH, p.mpH)
        assertEquals(minOf(1920 - p.bannerH - 18, 3 * (1200 * 0.4f).toInt() + 2 * 18), p.tilesH)
    }

    @Test fun aShortCardShrinksItsLinesRatherThanShowingFewer() {
        assertEquals(88, CardTags.rowPxFor(2000, 88, 2f))
        assertEquals(60, CardTags.rowPxFor(600, 88, 2f))
        assertEquals(60, CardTags.rowPxFor(300, 88, 2f)) // never below the minimum
    }

    @Test fun chipsStartAtTheTitleWithEqualGapsAndOnlyTheLastGivesWay() {
        val gap = 20; val sep = 2
        val natural = listOf(110, 90, 140)
        // room enough: the first chip starts at the title's x (0), equal gaps, nothing shortened
        val roomy = CardTags.chipSlots(natural, 1000, gap, sep)
        assertEquals(0, roomy[0][0])
        assertEquals(natural, roomy.map { it[1] })
        val gaps = (1 until roomy.size).map { roomy[it][0] - (roomy[it - 1][0] + roomy[it - 1][1]) }
        assertTrue("gaps $gaps", gaps.all { it == gap + sep + gap })
        // a narrow row: the earlier chips keep their width, the last is cut to what is left
        val tight = CardTags.chipSlots(natural, 330, gap, sep)
        assertEquals(listOf(110, 90), tight.take(2).map { it[1] })
        assertTrue("last ${tight[2][1]}", tight[2][1] in 0 until 140 && tight[2][0] + tight[2][1] <= 330)
        assertEquals(roomy.map { it[0] }, tight.map { it[0] })
    }
}
