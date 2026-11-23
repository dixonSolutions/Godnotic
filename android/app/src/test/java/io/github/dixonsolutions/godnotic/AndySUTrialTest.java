package io.github.dixonsolutions.godnotic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The unconfirmed-trial handling behind Settings > Updates (AndySUState.TrialGate and friends):
 * the 1289 report where Apply live said "Still confirming the current version; try again after
 * the game starts" to a player already in the game, for as long as the process lived.
 */
public class AndySUTrialTest {
    private static final boolean[] ALL = {false, true, true, true};
    private static final boolean[] NO_API = {false, false, true, true};
    private static final boolean[] NONE = {false, false, false, false};

    private static final long S = 1000;

    @Test public void aTrialIsConfirmedAfterItHasRunLongEnoughNotBefore() {
        AndySUState.TrialGate g = new AndySUState.TrialGate();
        assertEquals(0, g.poll("a@1", 0, -1, false, ALL));
        assertEquals(0, g.poll("a@1", 59 * S, -1, false, ALL));
        assertEquals(AndySUState.CONFIRM_API, g.poll("a@1", 60 * S, -1, false, ALL));
        assertEquals("not again at once: the host commits on its main thread", 0, g.poll("a@1", 61 * S, -1, false, ALL));
        assertFalse("not stuck while the first try is read back", g.stuck(61 * S));
    }

    @Test public void aLongRunningProcessDoesNotConfirmAFreshLiveSwapAtOnce() {
        // The process is hours old; the live swap opened this trial a second ago. The old rule
        // (process uptime) committed it instantly, before the new engine had started.
        AndySUState.TrialGate g = new AndySUState.TrialGate();
        long hours = 5L * 3600 * S;
        assertEquals(0, g.poll("b@9", hours, 1 * S, false, ALL));
        assertEquals(0, g.poll("b@9", hours + 30 * S, 31 * S, false, ALL));
        assertEquals(AndySUState.CONFIRM_API, g.poll("b@9", hours + 60 * S, 61 * S, false, ALL));
    }

    @Test public void theHostsAgeOfTheTrialHoldsItBackToo() {
        AndySUState.TrialGate g = new AndySUState.TrialGate();
        g.poll("d@5", 0, 10 * S, false, ALL);
        // seen for 60 s here, but the host says it began 20 s ago (a swap in between): wait
        assertEquals(0, g.poll("d@5", 60 * S, 20 * S, false, ALL));
        assertEquals(AndySUState.CONFIRM_API, g.poll("d@5", 61 * S, 61 * S, false, ALL));
    }

    @Test public void aSwapInFlightRestartsTheClock() {
        AndySUState.TrialGate g = new AndySUState.TrialGate();
        g.poll("e@1", 0, -1, false, ALL);
        assertEquals(0, g.poll("e@1", 70 * S, -1, true, ALL));
        assertEquals(0, g.poll("e@1", 100 * S, -1, false, ALL));
        assertEquals(AndySUState.CONFIRM_API, g.poll("e@1", 130 * S, -1, false, ALL));
    }

    @Test public void aNewTrialStartsItsOwnClock() {
        AndySUState.TrialGate g = new AndySUState.TrialGate();
        g.poll("f@1", 0, -1, false, ALL);
        assertEquals(AndySUState.CONFIRM_API, g.poll("f@1", 60 * S, -1, false, ALL));
        assertEquals(0, g.poll("", 61 * S, -1, false, ALL));
        assertEquals(0, g.poll("f@2", 62 * S, -1, false, ALL));
        assertEquals(0, g.poll("f@2", 100 * S, -1, false, ALL));
        assertEquals(AndySUState.CONFIRM_API, g.poll("f@2", 122 * S, -1, false, ALL));
    }

    @Test public void everyWayIsTriedThenTheScreenStopsWaiting() {
        AndySUState.TrialGate g = new AndySUState.TrialGate();
        g.poll("g@1", 0, -1, false, ALL);
        assertEquals(AndySUState.CONFIRM_API, g.poll("g@1", 60 * S, -1, false, ALL));
        assertEquals(AndySUState.CONFIRM_HOST, g.poll("g@1", 65 * S, -1, false, ALL));
        assertEquals(AndySUState.CONFIRM_CORE, g.poll("g@1", 70 * S, -1, false, ALL));
        assertFalse("the last way still has to be read back", g.stuck(71 * S));
        assertTrue(g.stuck(75 * S));
        // and it keeps trying, slowly, in case the host recovers
        assertEquals(0, g.poll("g@1", 90 * S, -1, false, ALL));
        assertEquals(AndySUState.CONFIRM_API, g.poll("g@1", 100 * S, -1, false, ALL));
    }

    @Test public void anOldHostWithoutMarkHealthyStillGetsTheOtherWays() {
        AndySUState.TrialGate g = new AndySUState.TrialGate();
        g.poll("h@1", 0, -1, false, NO_API);
        assertEquals(AndySUState.CONFIRM_HOST, g.poll("h@1", 60 * S, -1, false, NO_API));
        assertEquals(AndySUState.CONFIRM_CORE, g.poll("h@1", 65 * S, -1, false, NO_API));
        assertTrue(g.stuck(70 * S));
    }

    @Test public void aHostWithNoWayToConfirmIsStuckAtOnceNotAfterAFewMinutes() {
        AndySUState.TrialGate g = new AndySUState.TrialGate();
        g.poll("i@1", 0, -1, false, NONE);
        assertFalse("still inside the stable window", g.stuck(30 * S));
        assertEquals(0, g.poll("i@1", 60 * S, -1, false, NONE));
        assertTrue(g.stuck(60 * S));
    }

    @Test public void aClosedTrialResetsEverything() {
        AndySUState.TrialGate g = new AndySUState.TrialGate();
        g.poll("j@1", 0, -1, false, ALL);
        g.poll("j@1", 60 * S, -1, false, ALL);
        assertEquals(0, g.poll("", 62 * S, -1, false, ALL));
        assertFalse(g.stuck(200 * S));
        assertEquals(0, g.waitMs(200 * S, -1));
    }

    @Test public void waitMsCountsDownFromTheLaterOfTheTwoClocks() {
        AndySUState.TrialGate g = new AndySUState.TrialGate();
        g.poll("k@1", 100 * S, 5 * S, false, ALL);
        assertEquals(60 * S, g.waitMs(100 * S, -1));
        assertEquals(60 * S, g.waitMs(100 * S, 5 * S));
        assertEquals(55 * S, g.waitMs(120 * S, 5 * S));
        assertEquals(40 * S, g.waitMs(120 * S, 20 * S));
        assertEquals(0, g.waitMs(170 * S, 75 * S));
    }

    @Test public void trialAgeComesFromTheHostsEpochSeconds() {
        assertEquals(-1, AndySUState.trialAgeMs(0, 1_000_000));
        assertEquals(30 * S, AndySUState.trialAgeMs(1000, 1_030_000));
        assertEquals("a clock that went back", -1, AndySUState.trialAgeMs(2000, 1_000_000));
        assertEquals("nonsense", -1, AndySUState.trialAgeMs(1, 4_000_000_000_000L));
    }

    @Test public void theAppsEngineSwapDoesNotNeedTheHostTrialOnceConfirmingFailed() {
        // host swap (native-only release): the host refuses while the trial is open
        assertTrue(AndySUState.trialBlocksLive(true, true, false));
        assertTrue(AndySUState.trialBlocksLive(true, true, true));
        // engine and data swap from the app (the Java code changed, the owner's case): waits only
        // while the confirmation is under way, then goes ahead
        assertTrue(AndySUState.trialBlocksLive(true, false, false));
        assertFalse(AndySUState.trialBlocksLive(true, false, true));
        assertFalse("no trial, no block", AndySUState.trialBlocksLive(false, true, false));
    }

    @Test public void aStickyApplyRefusedNoLongerBlocksTheScreen() {
        assertEquals("staged", AndySUState.settleApplyRefused("apply_refused", "309"));
        assertEquals("idle", AndySUState.settleApplyRefused("apply_refused", ""));
        assertEquals("other states pass through", "downloading", AndySUState.settleApplyRefused("downloading", "309"));
        assertEquals("error", AndySUState.settleApplyRefused("error", ""));
    }

    @Test public void messagesNeverSendAPlayerInTheGameBackToTheGameStart() {
        String[] all = {
            AndySUState.trialWaitText(40_000), AndySUState.trialWaitText(0), AndySUState.trialStuckText(),
            AndySUState.applyRefusedMessage(),
            AndySUState.applyProblem(true, true, "309", ""),
        };
        for (String m : all) {
            assertFalse(m, m.contains("after the game starts"));
            assertFalse(m, m.contains("Still confirming the current version"));
        }
        assertTrue(AndySUState.trialWaitText(40_000).contains("40 s"));
        assertTrue("a way out is named", AndySUState.trialStuckText().contains("Restart now"));
    }
}
