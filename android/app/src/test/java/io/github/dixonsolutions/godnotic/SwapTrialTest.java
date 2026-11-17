package io.github.dixonsolutions.godnotic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** The trial marker has to be on disk before the swap and still there after a kill. */
public class SwapTrialTest {
    private File dir;

    @Before public void makeDir() throws IOException {
        dir = Files.createTempDirectory("swap-trial").toFile();
    }

    @After public void cleanUp() {
        delete(dir);
    }

    @Test public void aFreshDeviceClassMayTry() {
        assertFalse(SwapTrial.trial(dir, "abc123"));
        assertFalse(SwapTrial.bad(dir, "abc123"));
    }

    @Test public void beginIsOnDiskBeforeTheNativeRequest() {
        assertTrue(SwapTrial.begin(dir, "abc123"));
        // A new look at the same directory (the next process) sees the trial.
        assertTrue(SwapTrial.trial(dir, "abc123"));
        assertFalse(SwapTrial.bad(dir, "abc123"));
    }

    @Test public void healthyClearsTheTrialAndLeavesTheDeviceUsable() {
        assertTrue(SwapTrial.begin(dir, "abc123"));
        SwapTrial.healthy(dir, "abc123");
        assertFalse(SwapTrial.trial(dir, "abc123"));
        assertFalse(SwapTrial.bad(dir, "abc123"));
    }

    @Test public void aFailureIsDurableAndKeepsTheDeviceOff() {
        assertTrue(SwapTrial.begin(dir, "abc123"));
        SwapTrial.failed(dir, "abc123");
        assertFalse(SwapTrial.trial(dir, "abc123"));
        assertTrue(SwapTrial.bad(dir, "abc123"));
        // What the next start reads (no preference cache involved).
        assertTrue(SwapTrial.bad(dir, "abc123"));
    }

    @Test public void aRefusedRequestLeavesNoTrial() {
        assertTrue(SwapTrial.begin(dir, "abc123"));
        SwapTrial.refused(dir, "abc123");
        assertFalse(SwapTrial.trial(dir, "abc123"));
        assertFalse(SwapTrial.bad(dir, "abc123"));
    }

    @Test public void aKilledTrialIsSeenAsUnfinishedOnTheNextStart() {
        assertTrue(SwapTrial.begin(dir, "abc123"));
        // No healthy() and no failed(): the process was killed inside the swap. The marker is the
        // only evidence, and the next start must treat it as a failed class.
        assertTrue(SwapTrial.trial(dir, "abc123"));
        assertFalse(SwapTrial.bad(dir, "abc123"));
    }

    @Test public void deviceKeysStayInsideTheDirectory() {
        assertTrue(SwapTrial.begin(dir, "../../etc/passwd"));
        File[] files = dir.listFiles();
        assertTrue(files != null && files.length == 1);
        assertFalse(files[0].getName().contains("/"));
        assertFalse(files[0].getName().contains(".."));
    }

    @Test public void aHalfWrittenTrialIsNotMistakenForNone() throws IOException {
        // A crash between creating the temporary file and renaming it leaves only the .tmp. It is
        // not a trial: the next process must not act on a half-written marker.
        Files.write(new File(dir, "trial-key.tmp").toPath(), "trial".getBytes(StandardCharsets.UTF_8));
        assertFalse(SwapTrial.trial(dir, "key"));
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
