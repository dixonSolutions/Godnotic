package io.github.dixonsolutions.godnotic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The apply order: quiet, mark replacing, relaunch — never relaunch before the app is quiet. */
public class ReplaceSequenceTest {
    @Test public void quietHappensBeforeTheRelaunch() {
        List<String> order = new ArrayList<>();
        ReplaceSequence.apply(() -> order.add("quiet"), () -> order.add("replacing"),
                () -> order.add("relaunch"));
        assertEquals(Arrays.asList("quiet", "replacing", "relaunch"), order);
    }

    @Test public void aFailedQuietStepStillAsksForTheRelaunch() {
        List<String> order = new ArrayList<>();
        try {
            ReplaceSequence.apply(() -> {
                order.add("quiet");
                throw new IllegalStateException("could not stop a service");
            }, () -> order.add("replacing"), () -> order.add("relaunch"));
            fail("the quiet step's failure should still be reported");
        } catch (IllegalStateException expected) {
            // the caller reports it; the relaunch has already happened
        }
        assertEquals(Arrays.asList("quiet", "relaunch"), order);
    }

    @Test public void theRelaunchStillRunsWhenMarkingReplacingFails() {
        List<String> order = new ArrayList<>();
        try {
            ReplaceSequence.apply(() -> order.add("quiet"), () -> {
                order.add("replacing");
                throw new IllegalStateException("no looper");
            }, () -> order.add("relaunch"));
            fail("the failure should reach the caller");
        } catch (IllegalStateException expected) {
            // expected
        }
        assertEquals(Arrays.asList("quiet", "replacing", "relaunch"), order);
    }

    @Test public void aMissingRelaunchIsNotSwallowed() {
        boolean reached = false;
        try {
            ReplaceSequence.apply(() -> {
            }, () -> {
            }, () -> {
                throw new IllegalStateException("no host");
            });
        } catch (IllegalStateException expected) {
            reached = true;
        }
        assertTrue(reached);
    }
}
