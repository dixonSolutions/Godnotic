package io.github.dixonsolutions.godnotic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** What a package replacement invalidates, without Android. */
public class PackageReplaceTest {

    @Test
    public void aDifferentOrMissingStampIsAReplacement() {
        assertTrue(PackageReplace.replaced("1.2.260", "1.2.261"));
        assertTrue("first start ever", PackageReplace.replaced("", "1.2.261"));
        assertTrue("first start of this install", PackageReplace.replaced(null, "1.2.261"));
        assertTrue("no version to compare", PackageReplace.replaced("1.2.260", null));
    }

    @Test
    public void theSameBuildIsAnOrdinaryRestart() {
        assertFalse(PackageReplace.replaced("1.2.261", "1.2.261"));
    }

    @Test
    public void versionsComeOutOfAssetNames() {
        assertEquals("1.2.261", PackageReplace.versionIn("Godnotic-live-1.2.261-arm64.apk"));
        assertEquals("1.2.262", PackageReplace.versionIn("Godnotic-1.2.262-x64.apk.part"));
        assertNull(PackageReplace.versionIn("notes.txt"));
        assertNull(PackageReplace.versionIn(null));
    }

    @Test
    public void theRunningBuildAndOlderOnesAreStaleButANewerOneStays() {
        List<String> cache = Arrays.asList(
                "Godnotic-live-1.2.261-arm64.apk",   // installed: gone
                "Godnotic-live-1.2.260-arm64.apk",   // older: gone
                "Godnotic-live-1.2.262-arm64.apk",   // newer: kept for the next start
                "Godnotic-live-1.2.262-arm64.apk.part",
                "leftover.txt");                     // nothing installable: gone
        assertEquals(Arrays.asList(
                        "Godnotic-live-1.2.261-arm64.apk",
                        "Godnotic-live-1.2.260-arm64.apk",
                        "leftover.txt"),
                PackageReplace.staleApks(cache, "1.2.261"));
        assertTrue(PackageReplace.staleApks(null, "1.2.261").isEmpty());
    }

    @Test
    public void everyOneShotRequestOfTheMenuIsCleared() {
        List<String> requests = Arrays.asList(PackageReplace.REQUESTS);
        assertTrue(requests.contains("update-request.txt"));
        assertTrue(requests.contains("andysu-update-request.txt"));
        assertTrue(requests.contains("crash-reports-request.txt"));
        assertEquals("none of these is a durable setting", 3, requests.size());
    }

    @Test
    public void theEngineIsAskedToQuitAndGivenAMoment() {
        assertEquals("quit", PackageReplace.QUIT);
        assertTrue("long enough to write a config", PackageReplace.QUIET_MS >= 300);
        assertTrue("short enough for an install button", PackageReplace.QUIET_MS <= 3000);
        List<String> state = Arrays.asList(PackageReplace.STATE);
        assertTrue(state.contains("data/touch_netstate"));
        assertTrue(state.contains("data/touch_update_game_state"));
    }
}
