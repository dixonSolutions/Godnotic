package io.github.dixonsolutions.godnotic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ProcessStateText: the summary line a dead process leaves for its exit reason. Plain JVM. */
public class ProcessStateTextTest {

    @Test public void everyPartIsNamedInAFixedOrder() {
        assertEquals("app=1.2.262 payload=1.2.262 host=present:auto stage=game engine=1 phase=install",
                ProcessStateText.format("1.2.262", "1.2.262", "present:auto", "game", true, "install"));
        // The same death with nothing else known still has the same shape.
        assertEquals("app=- payload=- host=- stage=- engine=0 phase=-",
                ProcessStateText.format(null, "", null, " ", false, null));
    }

    @Test public void aPartStaysOnOneLineAndShort() {
        String line = ProcessStateText.format("1.2.262\nsomething else", "p", "h", "s", false, "f");
        assertFalse(line.contains("\n"));
        assertEquals(1, line.split("\n").length);
        assertTrue(ProcessStateText.oneLine(new String(new char[200]).replace('\0', 'x')).length() <= 48);
    }

    @Test public void onlyARealChangeIsPublished() {
        String line = ProcessStateText.format("1.2.262", "1.2.262", "present", "game", true, "idle");
        assertFalse(ProcessStateText.changed(line, line));
        assertTrue(ProcessStateText.changed(line,
                ProcessStateText.format("1.2.262", "1.2.262", "present", "game", true, "replacing")));
        assertFalse(ProcessStateText.changed(line, null));
    }
}
