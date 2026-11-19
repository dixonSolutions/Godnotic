package io.github.dixonsolutions.godnotic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** One poll that throws must not end the process or the next poll. */
public class PollLoopTest {
    @Test public void aSuccessfulPollRunsOnce() {
        int[] runs = {0};
        boolean ok = PollLoop.once(() -> runs[0]++, error -> {
        });
        assertTrue(ok);
        assertEquals(1, runs[0]);
    }

    @Test public void anExceptionIsReportedAndContained() {
        IllegalStateException boom = new IllegalStateException("bad status");
        Throwable[] seen = {null};
        boolean ok = PollLoop.once(() -> {
            throw boom;
        }, error -> seen[0] = error);
        assertFalse(ok);
        assertSame(boom, seen[0]);
    }

    @Test public void anErrorIsContainedToo() {
        Throwable[] seen = {null};
        boolean ok = PollLoop.once(() -> {
            throw new NoClassDefFoundError("the package was replaced");
        }, error -> seen[0] = error);
        assertFalse(ok);
        assertTrue(seen[0] instanceof NoClassDefFoundError);
    }

    @Test public void aFailingReporterCannotEscape() {
        boolean ok = PollLoop.once(() -> {
            throw new RuntimeException("poll");
        }, error -> {
            throw new RuntimeException("reporter");
        });
        assertFalse(ok);
    }

    @Test public void theNextPollStillRuns() {
        int[] runs = {0};
        int[] failures = {0};
        Runnable poll = () -> {
            runs[0]++;
            if (runs[0] == 2) {
                throw new IllegalStateException("only this poll");
            }
        };
        PollLoop.Reporter reporter = error -> failures[0]++;
        for (int i = 0; i < 3; i++) {
            PollLoop.once(poll, reporter);
        }
        assertEquals(3, runs[0]);
        assertEquals(1, failures[0]);
    }
}
