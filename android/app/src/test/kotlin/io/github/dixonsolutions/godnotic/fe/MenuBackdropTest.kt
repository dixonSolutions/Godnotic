package io.github.dixonsolutions.godnotic.fe

import org.junit.Assert.assertEquals
import org.junit.Test

class MenuBackdropTest {
    @Test fun liveGameIsVisibleAndPausedGameIsDimmed() {
        assertEquals(0, MenuBackdrop.color(false))
        assertEquals(0xB3000000.toInt(), MenuBackdrop.color(true))
    }

    @Test fun onlineMatchKeepsBeingDrawnBehindTheMenu() {
        assertEquals("0", MenuBackdrop.cover(visible = true, overGame = false, inMatchMenu = true, paused = false))
    }

    @Test fun pausedLocalGameAndMainMenuCoverTheEngine() {
        assertEquals("2", MenuBackdrop.cover(visible = true, overGame = false, inMatchMenu = true, paused = true))
        assertEquals("2", MenuBackdrop.cover(visible = true, overGame = false, inMatchMenu = false, paused = false))
    }

    @Test fun dialogOverTheMatchAndHiddenUiAreSeeThrough() {
        assertEquals("0", MenuBackdrop.cover(visible = true, overGame = true, inMatchMenu = true, paused = false))
        assertEquals("0", MenuBackdrop.cover(visible = false, overGame = false, inMatchMenu = false, paused = false))
    }
}
