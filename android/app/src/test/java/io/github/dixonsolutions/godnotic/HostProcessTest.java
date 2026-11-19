package io.github.dixonsolutions.godnotic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The relaunch process check: plain JVM, no Android. */
public class HostProcessTest {
    @Test public void theHostRelaunchProcessIsRecognized() {
        assertTrue(HostProcess.isRelaunch("io.github.dixonsolutions.godnotic:andysu_phoenix"));
    }

    @Test public void theGameAndOtherProcessesAreNot() {
        assertFalse(HostProcess.isRelaunch("io.github.dixonsolutions.godnotic"));
        assertFalse(HostProcess.isRelaunch("io.github.dixonsolutions.godnotic:andysu_phoenix2"));
        assertFalse(HostProcess.isRelaunch("com.example.andysu_phoenix"));
        assertFalse(HostProcess.isRelaunch(""));
        assertFalse(HostProcess.isRelaunch(null));
    }
}
