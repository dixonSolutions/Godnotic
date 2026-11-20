package io.github.dixonsolutions.godnotic.fe

import io.github.dixonsolutions.godnotic.fe.PicturePolicy.First
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PicturePolicyTest {
    @Test fun aKnownPictureIsNeverPrecededByAPlaceholder() {
        assertEquals(First.SHOW, PicturePolicy.first(pathKnown = true, viewHasPicture = false))
        assertEquals(First.SHOW, PicturePolicy.first(pathKnown = true, viewHasPicture = true))
    }

    @Test fun anUnknownPictureShowsThePlaceholderOnlyOnAViewWithNothing() {
        assertEquals(First.PLACEHOLDER, PicturePolicy.first(pathKnown = false, viewHasPicture = false))
        // the card's map changed: the old picture stays until the new one is there
        assertEquals(First.KEEP, PicturePolicy.first(pathKnown = false, viewHasPicture = true))
    }

    @Test fun cardsDecodeTheirPictureNowButListsDoNot() {
        assertTrue(PicturePolicy.decodeNow(sync = true, cachedInMemory = false))
        assertFalse(PicturePolicy.decodeNow(sync = true, cachedInMemory = true))
        assertFalse(PicturePolicy.decodeNow(sync = false, cachedInMemory = false))
    }

    @Test fun theDialogNeverDecodesOnTheUiThread() {
        assertFalse(PicturePolicy.decodeNow(sync = true, cachedInMemory = false, blocking = false))
        assertTrue(PicturePolicy.decodeNow(sync = true, cachedInMemory = false, blocking = true))
    }

    @Test fun warmMapsAreDistinctNonEmptyAndCapped() {
        assertEquals(listOf("a", "b", "c"), PicturePolicy.warmMaps(listOf("a", "", "b", "a", "c", "d"), 3))
        assertEquals(emptyList<String>(), PicturePolicy.warmMaps(emptyList(), 5))
    }
}
