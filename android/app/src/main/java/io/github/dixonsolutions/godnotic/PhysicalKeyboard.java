package io.github.dixonsolutions.godnotic;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

/**
 * Whether a real keyboard is attached, for SDL's hidden text field (patched in by
 * {@code scripts/android-patch-sdl-java.sh}).
 *
 * <p>SDL keeps that field focused while the engine wants characters, and with a keyboard attached
 * the engine always does (vid_sdl.c, VID_ShowKeyboard). As a text field it handed the physical keys
 * to the input method first, and a keyboard app with physical-keyboard prediction (Samsung
 * Keyboard) kept them for its suggestion strip: in a match, W A S D went there and the game got
 * nothing (owner's tablet, 2026-11-20). With a keyboard attached the field says it is no text
 * editor ({@link InputType#TYPE_NULL}), so the input method passes the keys on as key events;
 * SDL's key handler commits each key's character itself, so console and chat still get text.
 */
public final class PhysicalKeyboard {
    private static volatile boolean attached;

    private PhysicalKeyboard() {}

    /** True while {@code XonoticActivity.reportInputDevices} counts a real keyboard. */
    public static boolean attached() {
        return attached;
    }

    /** The input type SDL's text field asks for: none with a keyboard attached, else its own. */
    public static int inputType(int sdlType) {
        return attached ? InputType.TYPE_NULL : sdlType;
    }

    /**
     * A keyboard came or went. A focused text field asks the input method to start again, so the
     * new input type applies now rather than at the next chat.
     */
    static void update(Context context, boolean now, View textEdit) {
        if (attached == now) {
            return;
        }
        attached = now;
        if (textEdit == null || !textEdit.hasFocus()) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.restartInput(textEdit);
        }
    }
}
