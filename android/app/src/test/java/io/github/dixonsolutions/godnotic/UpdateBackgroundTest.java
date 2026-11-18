package io.github.dixonsolutions.godnotic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A download that is already running must survive the app going to the background (the host's
 * mode), and a blob that answers nothing must read as a release still being published.
 */
public class UpdateBackgroundTest {

    @Test public void aRunningDownloadIsNotStoppedByLeavingTheApp() {
        // Automatic, Wi-Fi only, unmetered, nothing staged, in the background: held at check_only
        // (the app cannot see the network there). With a download running it has to keep going, or
        // backgrounding Godnotic would cancel it and leave .part files for the next start.
        assertEquals(AndySUState.MODE_AUTO,
                AndySUState.targetMode(true, true, false, false, false));
        assertEquals(AndySUState.MODE_AUTO,
                AndySUState.targetMode(true, true, false, false, false, true));
    }

    @Test public void theOtherCombinationsAreUnchanged() {
        // Staged: the next start promotes it, as before.
        assertEquals(AndySUState.MODE_AUTO, AndySUState.targetMode(true, true, false, true, false));
        // A metered connection stops it whether or not one was running: that is Wi-Fi only.
        assertEquals(AndySUState.MODE_CHECK_ONLY, AndySUState.targetMode(true, true, true, false, false));
        assertEquals(AndySUState.MODE_CHECK_ONLY, AndySUState.targetMode(true, true, true, false, false, true));
        // Automatic updates off never downloads.
        assertEquals(AndySUState.MODE_CHECK_ONLY, AndySUState.targetMode(false, false, false, false, false, true));
        // No Wi-Fi-only rule: auto on any network, in the background too.
        assertEquals(AndySUState.MODE_AUTO, AndySUState.targetMode(true, false, true, false, false, true));
        // In front on Wi-Fi it downloads, in front on mobile data it waits.
        assertEquals(AndySUState.MODE_AUTO, AndySUState.targetMode(true, true, false, false, true, true));
        // The five-argument form is the behaviour the screen has always had.
        for (boolean auto : new boolean[] { true, false })
            for (boolean wifi : new boolean[] { true, false })
                for (boolean metered : new boolean[] { true, false })
                    for (boolean staged : new boolean[] { true, false })
                        for (boolean foreground : new boolean[] { true, false }) {
                            String expected = !auto ? AndySUState.MODE_CHECK_ONLY
                                    : !wifi ? AndySUState.MODE_AUTO
                                    : !metered ? AndySUState.MODE_AUTO
                                    : (!foreground && staged) ? AndySUState.MODE_AUTO : AndySUState.MODE_CHECK_ONLY;
                            assertEquals(auto + "/" + wifi + "/" + metered + "/" + staged + "/" + foreground,
                                    expected, AndySUState.targetMode(auto, wifi, metered, staged, foreground));
                        }
    }

    @Test public void theBootScreenDoesNotRestartIntoAnUnpack() {
        // A staged release on the boot screen may apply at once...
        assertTrue(AndySUState.shouldAutoApplyAtBoot("staged", false, "live"));
        // ...but never while the new payload's data is being written: that restart is a kill
        // between two files of the bundle, and the next start finds a half-written one.
        assertTrue(!AndySUState.shouldAutoApplyAtBoot("staged", true, "live"));
        assertTrue(!AndySUState.shouldAutoApplyAtBoot("staged", true, "restart"));
        // Nothing staged, or a policy that only applies at the next start: no restart either way.
        assertTrue(!AndySUState.shouldAutoApplyAtBoot("downloading", false, "live"));
        assertTrue(!AndySUState.shouldAutoApplyAtBoot("staged", false, "next_start"));
        // No policy API (an older host): applying at once is what it does anyway.
        assertTrue(AndySUState.shouldAutoApplyAtBoot("staged", false, null));
    }

    @Test public void aBlobStillBeingUploadedIsNotReportedAsABrokenDownload() {
        String host = "java.io.IOException: could not download 9f2c4b1d8e0a7c5b3d6f";
        assertEquals(UpdateRetry.publishingMessage(), AndySUState.friendlyMessage(host));
        // Anything else keeps its own words: it is the only clue the player can report.
        assertEquals("signature expired", AndySUState.friendlyMessage("signature expired"));
        assertEquals("", AndySUState.friendlyMessage(null));
        assertEquals("one line", AndySUState.friendlyMessage(" one\nline "));
        assertTrue(AndySUState.friendlyMessage(host).contains("still being published"));
    }
}
