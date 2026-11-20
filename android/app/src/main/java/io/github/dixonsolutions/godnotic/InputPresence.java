package io.github.dixonsolutions.godnotic;

import java.util.List;

/**
 * Which of touch, keyboard and mouse the player has, from facts about each
 * listed input device. Pure so a unit test can run it: XonoticActivity fills
 * in {@link Dev} from InputDevice and sends the answer to the engine.
 *
 * A pointer only counts when a person can aim with it. The pad a tablet has
 * on its own touchscreen controller (a Galaxy Tab's sec_touchpad) is listed
 * all the time and is never a pointer, so it used to turn into a "mouse" the
 * moment a Bluetooth keyboard appeared, which switched the touch controls off
 * and left a keyboard player with no way to turn.
 */
final class InputPresence {
    private InputPresence() {}

    /** What the activity read from one InputDevice. */
    static final class Dev {
        boolean keyboard;       // a real alphabetic keyboard
        boolean pointer;        // lists as mouse, touchpad or relative mouse
        boolean touchpadOnly;   // a touchpad that is not also a mouse
        boolean external;       // plugged in or paired, not part of the device
        boolean chassisPad;     // named like a touchpad or trackpad
    }

    /** Result of {@link #evaluate}: bit 0 keyboard, bit 1 mouse. */
    static final class Result {
        final boolean keyboard;
        final boolean mouse;
        final int mouseCount;

        Result(boolean keyboard, int mouseCount) {
            this.keyboard = keyboard;
            this.mouse = mouseCount > 0;
            this.mouseCount = mouseCount;
        }
    }

    static Result evaluate(List<Dev> devices, boolean hardKeyboardHidden) {
        boolean keyboard = false;
        boolean externalKeyboard = false;
        for (Dev d : devices) {
            if (d.keyboard) {
                keyboard = true;
                externalKeyboard |= d.external;
            }
        }
        // A keyboard the system itself calls hidden -- a lid or slider shut,
        // a Chromebook folded into a tablet -- is not one the player can use,
        // and neither is the chassis pad that goes face-down with it.
        boolean folded = keyboard && hardKeyboardHidden;
        if (folded) {
            keyboard = false;
            externalKeyboard = false;
        }
        int mice = 0;
        for (Dev d : devices) {
            if (counts(d, keyboard, externalKeyboard, folded)) {
                mice++;
            }
        }
        return new Result(keyboard, mice);
    }

    private static boolean counts(Dev d, boolean keyboard, boolean externalKeyboard, boolean folded) {
        if (!d.pointer) {
            return false;
        }
        if (folded && d.chassisPad) {
            return false;
        }
        // A pad that is part of the device itself belongs to a laptop only
        // when its keyboard is built in too. Beside a keyboard the player
        // attached (or none at all) it is the tablet's own pad, not a mouse.
        if (d.touchpadOnly && !d.external && (!keyboard || externalKeyboard)) {
            return false;
        }
        return true;
    }
}
