package io.github.dixonsolutions.godnotic.fe

import org.junit.Assert.assertEquals
import org.junit.Test

class MenuBackdropTest {
    @Test fun liveGameIsVisibleAndPausedGameIsDimmed() {
        assertEquals(0, MenuBackdrop.color(false))
        assertEquals(0xB3000000.toInt(), MenuBackdrop.color(true))
    }
}
