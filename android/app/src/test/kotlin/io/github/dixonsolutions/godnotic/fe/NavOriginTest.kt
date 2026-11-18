// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.dixonsolutions.godnotic.fe

import org.junit.Assert.assertEquals
import org.junit.Test

/** Leaving a game or cancelling a join returns to the screen it started from. */
class NavOriginTest {

    @Test
    fun localGameReturnsToLocalGames() {
        assertEquals(NavOrigin.LOCAL, NavOrigin.leave("sp"))
        assertEquals(NavOrigin.LOCAL, NavOrigin.leave("sp.map"))
        assertEquals(NavOrigin.LOCAL, NavOrigin.leave("local"))
    }

    @Test
    fun campaignReturnsToCampaign() {
        assertEquals(NavOrigin.CAMPAIGN, NavOrigin.leave("campaign"))
    }

    @Test
    fun serverJoinReturnsToServerBrowser() {
        assertEquals(NavOrigin.SERVERS, NavOrigin.leave("mp"))
        assertEquals(NavOrigin.SERVERS, NavOrigin.leave("servers"))
    }

    @Test
    fun cancelledJoinReturnsToItsOrigin() {
        assertEquals(NavOrigin.LOCAL, NavOrigin.cancelJoin("sp"))
        assertEquals(NavOrigin.CAMPAIGN, NavOrigin.cancelJoin("campaign"))
        assertEquals(NavOrigin.SERVERS, NavOrigin.cancelJoin("mp"))
    }

    @Test
    fun noOriginFallsBackToHome() {
        assertEquals(NavOrigin.HOME, NavOrigin.leave(null))
        assertEquals(NavOrigin.HOME, NavOrigin.leave(""))
        assertEquals(NavOrigin.HOME, NavOrigin.leave("deep-link"))
        assertEquals(NavOrigin.HOME, NavOrigin.cancelJoin(null))
    }

    @Test
    fun onlyLeavingAMatchSpendsTheOrigin() {
        // a join passes through loading and a moment of menu: the origin must survive that
        assertEquals(false, NavOrigin.consumesOrigin("loading"))
        assertEquals(false, NavOrigin.consumesOrigin("menu"))
        assertEquals(true, NavOrigin.consumesOrigin("game"))
    }

    @Test
    fun returnStackKeepsTheCategory() {
        assertEquals("home mp", NavOrigin.returnStack("home mp"))
        assertEquals("home sp sp.map", NavOrigin.returnStack("home sp sp.map"))
        assertEquals("home campaign", NavOrigin.returnStack("home campaign"))
        assertEquals(NavOrigin.HOME, NavOrigin.returnStack(""))
        assertEquals(NavOrigin.HOME, NavOrigin.returnStack(null))
        assertEquals(NavOrigin.HOME, NavOrigin.returnStack("home settings"))
    }
}
