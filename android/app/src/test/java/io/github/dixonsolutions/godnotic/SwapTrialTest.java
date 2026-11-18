package io.github.dixonsolutions.godnotic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** The trial marker has to be on disk before the swap, and a failure has to expire. */
public class SwapTrialTest {
    private File dir;
    private static final long MIN = 60_000L;

    @Before public void makeDir() throws IOException {
        dir = Files.createTempDirectory("swap-trial").toFile();
    }

    @After public void cleanUp() {
        delete(dir);
    }

    @Test public void aFreshDeviceClassMayTry() {
        assertFalse(SwapTrial.trial(dir, "abc123"));
        assertEquals(0, SwapTrial.attempts(dir, "abc123"));
        assertEquals(0, SwapTrial.retryAfter(dir, "abc123", 1_000_000L));
    }

    @Test public void beginIsOnDiskBeforeTheNativeRequest() {
        assertTrue(SwapTrial.begin(dir, "abc123"));
        // A new look at the same directory (the next process) sees the trial.
        assertTrue(SwapTrial.trial(dir, "abc123"));
    }

    @Test public void healthyClearsTheTrialAndTheFailureHistory() {
        assertTrue(SwapTrial.begin(dir, "abc123"));
        SwapTrial.failed(dir, "abc123", 1_000L, "1.2.270");
        SwapTrial.healthy(dir, "abc123");
        assertFalse(SwapTrial.trial(dir, "abc123"));
        assertEquals(0, SwapTrial.attempts(dir, "abc123"));
        assertEquals(0, SwapTrial.retryAfter(dir, "abc123", 1_001L));
    }

    @Test public void oneFailureWaitsTenMinutesAndThenRetries() {
        long t = 10 * MIN;
        SwapTrial.failed(dir, "abc123", t, "1.2.271");
        assertEquals(1, SwapTrial.attempts(dir, "abc123"));
        assertEquals("1.2.271", SwapTrial.failedVersion(dir, "abc123"));
        assertEquals(10 * MIN, SwapTrial.retryAfter(dir, "abc123", t));
        assertEquals(1, SwapTrial.retryAfter(dir, "abc123", t + 10 * MIN - 1));
        assertEquals(0, SwapTrial.retryAfter(dir, "abc123", t + 10 * MIN));
    }

    @Test public void repeatedFailuresBackOffFurtherAndCapAtSixHours() {
        long t = 1_000_000L;
        for (int i = 1; i <= 10; i++) SwapTrial.failed(dir, "abc123", t, "1.2.271");
        assertEquals(10, SwapTrial.attempts(dir, "abc123"));
        assertEquals(6 * 60 * MIN, SwapTrial.retryAfter(dir, "abc123", t));
        // An old failure is retried again: the device is never banned forever.
        assertEquals(0, SwapTrial.retryAfter(dir, "abc123", t + 7 * 60 * MIN));
    }

    @Test public void aClockThatWentBackwardsDoesNotWaitForever() {
        SwapTrial.failed(dir, "abc123", 5_000_000L, "1.2.271");
        long wait = SwapTrial.retryAfter(dir, "abc123", 4_000_000L);
        assertEquals(SwapTrial.backoff(1), wait);
    }

    @Test public void aRefusedRequestLeavesNoTrial() {
        assertTrue(SwapTrial.begin(dir, "abc123"));
        SwapTrial.refused(dir, "abc123");
        assertFalse(SwapTrial.trial(dir, "abc123"));
        assertEquals(0, SwapTrial.attempts(dir, "abc123"));
    }

    @Test public void aKilledTrialIsStillVisibleOnTheNextStart() {
        assertTrue(SwapTrial.begin(dir, "abc123"));
        // No healthy() and no failed(): the process was killed inside the swap. The marker is the
        // evidence; EngineSwap records it as a failure and the backoff takes over from there.
        assertTrue(SwapTrial.trial(dir, "abc123"));
        assertEquals(0, SwapTrial.attempts(dir, "abc123"));
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
