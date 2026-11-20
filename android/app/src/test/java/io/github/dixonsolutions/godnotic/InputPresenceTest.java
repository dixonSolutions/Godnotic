package io.github.dixonsolutions.godnotic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Keyboard without mouse on a tablet must stay keyboard without mouse. */
public class InputPresenceTest {

    private static InputPresence.Dev keyboard(boolean external) {
        InputPresence.Dev d = new InputPresence.Dev();
        d.keyboard = true;
        d.external = external;
        return d;
    }

    private static InputPresence.Dev pad(boolean external, boolean chassisNamed) {
        InputPresence.Dev d = new InputPresence.Dev();
        d.pointer = true;
        d.touchpadOnly = true;
        d.external = external;
        d.chassisPad = chassisNamed;
        return d;
    }

    private static InputPresence.Dev mouse() {
        InputPresence.Dev d = new InputPresence.Dev();
        d.pointer = true;
        d.external = true;
        return d;
    }

    private static InputPresence.Result run(boolean hidden, InputPresence.Dev... devs) {
        List<InputPresence.Dev> list = new ArrayList<>();
        for (InputPresence.Dev d : devs) {
            list.add(d);
        }
        return InputPresence.evaluate(list, hidden);
    }

    @Test
    public void tabletOwnPadBesideBluetoothKeyboardIsNotAMouse() {
        // The owner's case: Galaxy Tab sec_touchpad plus a paired keyboard.
        InputPresence.Result r = run(false, pad(false, true), keyboard(true));
        assertTrue(r.keyboard);
        assertFalse(r.mouse);
    }

    @Test
    public void tabletOwnPadAloneIsNotAMouse() {
        assertFalse(run(false, pad(false, true)).mouse);
    }

    @Test
    public void realMouseBesideKeyboardIsAMouse() {
        InputPresence.Result r = run(false, pad(false, true), keyboard(true), mouse());
        assertTrue(r.keyboard);
        assertTrue(r.mouse);
    }

    @Test
    public void externalPadCounts() {
        assertTrue(run(false, keyboard(true), pad(true, true)).mouse);
    }

    @Test
    public void laptopPadWithBuiltInKeyboardCounts() {
        InputPresence.Result r = run(false, keyboard(false), pad(false, true));
        assertTrue(r.keyboard);
        assertTrue(r.mouse);
    }

    @Test
    public void foldedCoverTakesItsPadWithIt() {
        InputPresence.Result r = run(true, keyboard(false), pad(false, true));
        assertFalse(r.keyboard);
        assertFalse(r.mouse);
    }
}
