package io.github.dixonsolutions.godnotic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The live-swap state machine and the per-device gate: plain JVM, no Android. */
public class LiveSwapGuardTest {
    @Test public void healthySwapCommitsOnlyAfterStarted() {
        LiveSwapGuard g = new LiveSwapGuard();
        assertTrue(g.accept(100, 90_000));
        assertEquals(LiveSwapGuard.State.ACCEPTED, g.state());
        assertFalse(g.expire(89_999));
        assertTrue(g.started(1_000, 60_000));
        assertEquals(LiveSwapGuard.State.STARTED, g.state());
        assertFalse(g.started(1_000, 60_000)); // a duplicate started does not reset anything
        assertTrue(g.healthy());
        assertEquals(LiveSwapGuard.State.HEALTHY, g.state());
        assertFalse(g.active());
        assertFalse(g.failed());
        assertFalse(g.expire(999_999));
    }

    @Test public void timeoutBeforeStartExpires() {
        LiveSwapGuard g = new LiveSwapGuard();
        assertTrue(g.accept(0, 10_000));
        assertFalse(g.expire(9_999));
        assertTrue(g.expire(10_000));
        assertEquals(LiveSwapGuard.State.EXPIRED, g.state());
        assertFalse(g.started(10_001, 1_000));
        assertFalse(g.healthy());
        assertFalse(g.failed());
    }

    @Test public void startedTimeoutAndFailureAreTerminal() {
        LiveSwapGuard g = new LiveSwapGuard();
        assertTrue(g.accept(0, 90_000));
        assertTrue(g.started(5_000, 30_000));
        assertFalse(g.expire(34_999));
        assertTrue(g.expire(35_000));
        assertFalse(g.failed());

        LiveSwapGuard f = new LiveSwapGuard();
        assertTrue(f.accept(0, 90_000));
        assertTrue(f.failed());
        assertEquals(LiveSwapGuard.State.FAILED, f.state());
        assertFalse(f.healthy());
        assertFalse(f.started(1, 1));
    }

    @Test public void onlyTheFirstAcceptAndHealthyCount() {
        LiveSwapGuard g = new LiveSwapGuard();
        assertFalse(g.started(0, 1));
        assertFalse(g.healthy());
        assertTrue(g.accept(0, 90_000));
        assertFalse(g.accept(1, 90_000));
        assertTrue(g.healthy());
        assertFalse(g.healthy());
        assertFalse(g.started(2, 1));
    }

    @Test public void aSecondSwapIsRefusedWhileOneIsInFlight() {
        LiveSwapGuard g = new LiveSwapGuard();
        assertTrue(g.canAccept());
        assertTrue(g.accept(0, 90_000));
        assertFalse("one is already being applied", g.canAccept());
        assertFalse("never two swaps at once", g.accept(1, 90_000));
        assertTrue(g.started(2, 60_000));
        assertFalse(g.canAccept());
        assertTrue(g.healthy());
        // A finished healthy swap leaves the process able to take the next release.
        assertTrue(g.canAccept());
        assertTrue(g.accept(3, 90_000));
    }

    @Test public void aFailedTrialNeverAcceptsAgainInThisProcess() {
        LiveSwapGuard g = new LiveSwapGuard();
        assertTrue(g.accept(0, 90_000));
        assertTrue(g.failed());
        assertFalse(g.canAccept());
        assertFalse(g.accept(1, 90_000));
    }

    @Test public void deviceGateDefaultsOnAndTurnsOffAfterACrash() {
        assertTrue(LiveSwapGuard.allowed(true, true, false, false));
        assertFalse("no host API level 2", LiveSwapGuard.allowed(false, true, false, false));
        assertFalse("user setting off", LiveSwapGuard.allowed(true, false, false, false));
        assertFalse("this device class failed before", LiveSwapGuard.allowed(true, true, true, false));
        assertFalse("a previous trial never became healthy", LiveSwapGuard.allowed(true, true, false, true));
    }
}
